/**
 * Copyright (C) 2017 Matthias Wessendorf.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.wessendorf.kafka.cdi.extension;

import net.wessendorf.kafka.SimpleKafkaProducer;
import net.wessendorf.kafka.cdi.annotation.KafkaConfig;
import net.wessendorf.kafka.cdi.annotation.Consumer;
import net.wessendorf.kafka.cdi.annotation.Producer;
import net.wessendorf.kafka.impl.DelegationKafkaConsumer;
import net.wessendorf.kafka.impl.InjectedKafkaProducer;
import net.wessendorf.kafka.serialization.CafdiSerdes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.spi.AfterDeploymentValidation;
import javax.enterprise.inject.spi.AnnotatedMethod;
import javax.enterprise.inject.spi.AnnotatedType;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.Extension;
import javax.enterprise.inject.spi.InjectionPoint;
import javax.enterprise.inject.spi.InjectionTarget;
import javax.enterprise.inject.spi.ProcessAnnotatedType;
import javax.enterprise.inject.spi.ProcessInjectionTarget;
import javax.enterprise.inject.spi.WithAnnotations;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import java.lang.reflect.ParameterizedType;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static java.util.Collections.newSetFromMap;
import static net.wessendorf.kafka.cdi.extension.VerySimpleEnvironmentResolver.simpleBootstrapServerResolver;
import static org.apache.kafka.clients.CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG;

public class KafkaExtension<X> implements Extension {

    private String bootstrapServers = null;
    private final Set<AnnotatedMethod<?>> listenerMethods = newSetFromMap(new ConcurrentHashMap<>());
    private final Logger logger = LoggerFactory.getLogger(KafkaExtension.class);


    public void kafkaConfig(@Observes @WithAnnotations(KafkaConfig.class) ProcessAnnotatedType pat) {
        logger.trace("Kafka config scanning type: " + pat.getAnnotatedType().getJavaClass().getName());

        final AnnotatedType<X> annotatedType = pat.getAnnotatedType();
        final KafkaConfig kafkaConfig = annotatedType.getAnnotation(KafkaConfig.class);

        // we just do the first
        if (kafkaConfig != null && bootstrapServers == null) {
            logger.info("setting bootstrap.servers IP");
            logger.trace("Got '{}'", kafkaConfig.bootstrapServers());
            bootstrapServers = simpleBootstrapServerResolver(kafkaConfig.bootstrapServers());
        }
    }

    public void registerListeners(@Observes @WithAnnotations(Consumer.class) ProcessAnnotatedType pat) {

        logger.trace("scanning type: " + pat.getAnnotatedType().getJavaClass().getName());
        final AnnotatedType<X> annotatedType = pat.getAnnotatedType();


        for (AnnotatedMethod am : annotatedType.getMethods()) {

            if (am.isAnnotationPresent(Consumer.class)) {

                logger.debug("found annotated listener method, adding for further processing");

                listenerMethods.add(am);
            }
        }
    }

    public void afterDeploymentValidation(@Observes AfterDeploymentValidation adv, final BeanManager bm) {

//        final BeanManager bm = CDI.current().getBeanManager();

        logger.debug("wiring annotated listener method to internal Kafka Consumer");
        listenerMethods.forEach( am -> {
            final DelegationKafkaConsumer abc = new DelegationKafkaConsumer(bootstrapServers, am, bm);

            submitToExecutor(abc);
        });
    }

    public <X> void processInjectionTarget(@Observes ProcessInjectionTarget<X> pit) {

        final InjectionTarget<X> it = pit.getInjectionTarget();
        final AnnotatedType<X> at = pit.getAnnotatedType();

        final InjectionTarget<X> wrapped = new InjectionTarget<X>() {
            @Override
            public void inject(X instance, CreationalContext<X> ctx) {
                it.inject(instance, ctx);

                Arrays.asList(at.getJavaClass().getDeclaredFields()).forEach(field -> {
                    final Producer annotation = field.getAnnotation(Producer.class);

                    if (annotation != null) {
                        final String defaultTopic = annotation.topic();

                        if (field.getType().isAssignableFrom(SimpleKafkaProducer.class)) {
                            field.setAccessible(Boolean.TRUE);

                            final org.apache.kafka.clients.producer.Producer p = createInjectionProducer(
                                    bootstrapServers,
                                    defaultTopic,
                                    CafdiSerdes.serdeFrom((Class<?>)  ((ParameterizedType)field.getGenericType()).getActualTypeArguments()[0]).serializer().getClass(),
                                    CafdiSerdes.serdeFrom((Class<?>)  ((ParameterizedType)field.getGenericType()).getActualTypeArguments()[1]).serializer().getClass());

                            try {
                                field.set(instance, p);
                            } catch (IllegalArgumentException
                                    | IllegalAccessException e) {
                                logger.error("could not inject producer", e);
                                e.printStackTrace();
                            }
                        }
                    }
                });
            }

            @Override
            public void postConstruct(X instance) {
                it.postConstruct(instance);
            }

            @Override
            public void preDestroy(X instance) {
                it.dispose(instance);
            }

            @Override
            public void dispose(X instance) {
                it.dispose(instance);
            }

            @Override
            public Set<InjectionPoint> getInjectionPoints() {
                return it.getInjectionPoints();
            }

            @Override
            public X produce(CreationalContext<X> ctx) {
                return it.produce(ctx);
            }
        };


        pit.setInjectionTarget(wrapped);
    }

    private void submitToExecutor(final DelegationKafkaConsumer delegationKafkaConsumer) {

        ExecutorService executorService;
        try {
            executorService = InitialContext.doLookup("java:comp/DefaultManagedExecutorService");
        } catch (NamingException e) {
            logger.warn("Could not find a managed ExecutorService, creating one manually");
            executorService = new ThreadPoolExecutor(16, 16, 10, TimeUnit.MINUTES, new LinkedBlockingDeque<Runnable>());
        }

        // hooking it all together
        delegationKafkaConsumer.initialize();

        // and submit the consumer
        executorService.submit(delegationKafkaConsumer);
    }

    private org.apache.kafka.clients.producer.Producer createInjectionProducer(final String bootstrapServers, final String topic, final Class<?> keySerializerClass, final Class<?> valSerializerClass) {

        final Properties properties = new Properties();
        properties.put(BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(KEY_SERIALIZER_CLASS_CONFIG, keySerializerClass);
        properties.put(VALUE_SERIALIZER_CLASS_CONFIG, valSerializerClass);

        return new InjectedKafkaProducer(properties, topic);
    }


}
