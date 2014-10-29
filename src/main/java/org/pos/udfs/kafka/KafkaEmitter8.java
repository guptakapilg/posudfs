package org.pos.udfs.kafka;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import kafka.consumer.ConsumerIterator;
import kafka.javaapi.consumer.ConsumerConnector;
import kafka.message.MessageAndMetadata;

import org.apache.log4j.Logger;
import org.apache.pig.backend.hadoop.executionengine.storm.batch.BatchIdentifier;
import org.apache.pig.backend.hadoop.executionengine.storm.context.Context;
import org.apache.pig.backend.hadoop.executionengine.storm.context.POSCollector;
import org.apache.pig.backend.hadoop.executionengine.storm.spout.LoadFuncCompliantEmitter;
import org.apache.pig.data.TupleFactory;
import org.apache.pig.impl.PigContext;


public class KafkaEmitter8 extends LoadFuncCompliantEmitter<Object> {
	private static final long serialVersionUID = 1L;

    private static final String KAFKA_ZOOKEEPER_HOSTS = "kafka.zookeeper.hosts";
    
	private static Logger logger = Logger.getLogger(KafkaEmitter8.class);
	private static TupleFactory tupleFactory = TupleFactory.getInstance();

	transient protected ConsumerIterator<byte[], byte[]> streamIterator;
	private LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>(
			10000); // don't read in too much data from queue;
	private String kafkaTopic;
	private Semaphore numReaderController = new Semaphore(1);
	private int batchSizeSecs;
	private Context context;
	
	public KafkaEmitter8(String batchSizeSecs) {
		this.batchSizeSecs = Integer.parseInt(batchSizeSecs);
	}

	@Override
	public void prepare(Context context, String queueID) {
	    this.context = context;
		kafkaTopic = queueID;
	}

	@Override
	public Object emitBatchPartition(BatchIdentifier batchId,
			POSCollector collector, Object previousBatchMeta) {
		long startTime = System.currentTimeMillis() / 1000;
		long delta = startTime % (batchSizeSecs);
		long endTime = startTime - delta + (batchSizeSecs);

		logger.info("Emitting New Batch [" + batchId + "] tuples for "
				+ kafkaTopic + " batch boundary: " + startTime + "-" + endTime);

		if (numReaderController.tryAcquire()) {
			Thread kafkaReaderThread = new Thread(new KafkaReaderRunnable(),
					"KafkaReaderThread");
			kafkaReaderThread.setDaemon(true); /*
												 * So it dies with the main
												 * thread
												 */
			kafkaReaderThread.start();
		}

		/*
		 * IMPORTANT: The Kafka iterator.hasNext() is blocking.
		 * 
		 * We could set a consumer.timeout.ms in the KafkaConsumerFactory and
		 * catch the ConsumerTimeoutException. The issue is that once the
		 * iterator has timed out, it cannot be reused and will be in a FAILED
		 * state (IllegalStateException). We will need to reconnect to the Kafka
		 * stream again.
		 * 
		 * This is a known issue: http://issues.apache.org/jira/browse/KAFKA-241
		 * 
		 * Also important, when creating streams rapidly in succession, the
		 * offset is incorrectly advanced and we lose messages.
		 * 
		 * http://issues.apache.org/jira/browse/KAFKA-242
		 */

		int numEmitted = 0;

		while (System.currentTimeMillis() / 1000 < endTime) {
			try {
				String msg = messages.poll(500, TimeUnit.MILLISECONDS);

				if (msg != null) {
					collector.emit(tupleFactory.newTuple(msg));
					numEmitted++;
				}
			} catch (InterruptedException e) {
				/* Queue is empty */
				logger.error("Error while emitting from Kafka", e);
			}
		}
		logger.info("Emitted " + numEmitted + " tuples for " + kafkaTopic
				+ " batch boundary: " + startTime + "-" + endTime);

		return null;
	}

	@Override
	public void replayBatchPartition(BatchIdentifier batchId,
			POSCollector collector, Object lastPlayedBatchMeta) {
		logger.info("Replay for batch " + batchId + ", Ignoring");

	}

	class KafkaReaderRunnable implements Runnable {

		@SuppressWarnings("unchecked")
		@Override
		public void run() {
			ConsumerConnector consumerConnector = null;

			try {
				String consumerGroup = (String) context.getPigContext().getProperties().get(PigContext.JOB_NAME);
				String kafkaZookeeperHosts = (String) context.getPigContext().getProperties().get(KAFKA_ZOOKEEPER_HOSTS);
		        
		        if (kafkaZookeeperHosts == null) {
		            throw new RuntimeException(KAFKA_ZOOKEEPER_HOSTS + " not present in properties file, quitting");
		        }

				List<Object> ret = KafkaUtils.createStreamIterator(
						consumerGroup, kafkaTopic, kafkaZookeeperHosts);

				consumerConnector = (ConsumerConnector) ret.get(0);
				streamIterator = (ConsumerIterator<byte[], byte[]>) ret.get(1);

				while (streamIterator.hasNext()) {
				  MessageAndMetadata<byte[], byte[]> m = streamIterator.next();

					try {
						messages.offer(new String(m.message()), 1, TimeUnit.DAYS);
					} catch (InterruptedException e) {
						logger.error("Interrupted while reading from Kafka", e);
					}
				}
			} catch (Throwable t) {
				logger.warn("Error reading from Kafka", t);
			} finally {
				numReaderController.release();

				if (consumerConnector != null) {
					consumerConnector.shutdown();
				}
			}
		}
	}

}
