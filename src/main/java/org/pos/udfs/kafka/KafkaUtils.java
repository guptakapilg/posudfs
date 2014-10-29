package org.pos.udfs.kafka;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import kafka.consumer.Consumer;
import kafka.consumer.ConsumerConfig;
import kafka.consumer.ConsumerIterator;
import kafka.consumer.KafkaStream;
import kafka.javaapi.consumer.ConsumerConnector;

import org.apache.log4j.Logger;

public class KafkaUtils {
    private static transient Logger logger = Logger.getLogger(KafkaUtils.class);

    public static List<Object> createStreamIterator(String consumerGroup, String kafkaTopic,
        String zkConnectString) {
        logger.info("Registering a kafka consumer for topic " + kafkaTopic);

        Properties props = new Properties();
        // TODO: Configurable
        props.put("zookeeper.connect", zkConnectString);
        props.put("group.id", consumerGroup);
        props.put("auto.commit.interval.ms", "5000");
        props.put("zookeeper.session.timeout.ms", "60000");
        props.put("zookeeper.connection.timeout.ms", "60000");

        ConsumerConfig consumerConfig = new ConsumerConfig(props);
        ConsumerConnector consumerConnector = Consumer.createJavaConsumerConnector(consumerConfig);
        Map<String, Integer> m = new HashMap<>();
        
        m.put(kafkaTopic, 1);

        Map<String, List<KafkaStream<byte[], byte[]>>> consumerMap = consumerConnector.createMessageStreams(m);

        // WARN printing the consumerMap causes OutOfMemory, 
        //System.out.println("Kafka topics " + consumerMap);

        // FIXME: Single partition
        KafkaStream<byte[], byte[]> stream = consumerMap.get(kafkaTopic).get(0);
        ConsumerIterator<byte[], byte[]> it = stream.iterator();

        List<Object> list = new ArrayList<Object>();

        list.add(consumerConnector);
        list.add(it);

        return list;
    }
}
