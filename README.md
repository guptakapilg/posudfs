Pos UDFs
----------------
Out of the box, general purpose UDFs for PigOnStorm

-KafkaLoader usage:
register PosUdfs-jar-with-dependencies.jar;
set kafka.zookeeper.hosts 'kafka-zookeeper-hosts’;
A = load ‘topic’ using org.pos.udfs.kafka.KafkaEmitter8('batchSizeSeconds’);

