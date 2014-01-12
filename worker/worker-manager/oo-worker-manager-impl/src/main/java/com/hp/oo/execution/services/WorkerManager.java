package com.hp.oo.execution.services;

import com.hp.oo.engine.node.services.WorkerNodeService;
import com.hp.oo.orchestrator.services.configuration.WorkerConfigurationService;
import org.apache.commons.lang.ArrayUtils;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.io.File;
import java.io.FileFilter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static ch.lambdaj.Lambda.*;

/**
 * Created with IntelliJ IDEA.
 * User: kravtsov
 * Date: 20/11/12
 * Time: 11:02
 */
@Component("workerManager")
@DependsOn("workerRegistration")
public class WorkerManager implements ApplicationListener, EndExecutionCallback {
	private final Logger logger = Logger.getLogger(this.getClass());

	@Resource
	private String workerUuid;

	@Autowired
	private WorkerNodeService workerNodeService;

	@Autowired
	private WorkerConfigurationService workerConfigurationService;

	@Autowired
	private WorkerRecoveryManager recoveryManager;

	private LinkedBlockingQueue<Runnable> inBuffer = new LinkedBlockingQueue<>();

	@Autowired
	@Qualifier("numberOfExecutionThreads")
	private Integer numberOfThreads;

	@Autowired(required = false)
	@Qualifier("initStartUpSleep")
	private Long initStartUpSleep = 15*1000L; // by default 15 seconds

	@Autowired(required = false)
	@Qualifier("maxStartUpSleep")
	private Long maxStartUpSleep = 10*60*1000L; // by default 10 minutes

    private final static int KEEP_ALIVE_FAIL_LIMIT = 4;//

    private int keepAliveFailCount = 0;

	private ExecutorService executorService;

	@SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
	private Map<String, Future> mapOfRunningTasks;

	private boolean endOfInit = false;
	private boolean up = false;

	@PostConstruct
	private void init() {
		logger.info("Initialize worker with UUID: " + workerUuid);
		System.setProperty("worker.uuid", workerUuid); //do not remove!!!

		executorService = new ThreadPoolExecutor(numberOfThreads,
				numberOfThreads,
				Long.MAX_VALUE, TimeUnit.NANOSECONDS,
				inBuffer,
				new WorkerThreadFactory("WorkerExecutionThread"));
		mapOfRunningTasks = new ConcurrentHashMap<>(numberOfThreads);
	}

	public void addExecution(String executionId, Runnable runnable) {
		if (!recoveryManager.isInRecovery()) {
			Future future = executorService.submit(runnable);
			mapOfRunningTasks.put(executionId, future);
		}
	}

	@Override
	public void endExecution(String executionId) {
		mapOfRunningTasks.remove(executionId);
	}

	public int getInBufferSize() {
		return inBuffer.size();
	}

	@SuppressWarnings("unused") // @Scheduled(fixedRate=10000L)
	public void workerKeepAlive() {
		if (!recoveryManager.isInRecovery()) {
            SecurityTemplate securityTemplate = new SecurityTemplate();//TODO - remove this from score...
            securityTemplate.invokeSecured(new SecurityTemplate.SecurityTemplateCallback<Void>() {

                @Override
                public Void doSecured() {
                    if (endOfInit) {
                        try {
                            workerNodeService.keepAlive(workerUuid);
                            keepAliveFailCount = 0;
                        } catch (Exception e) {
                            keepAliveFailCount++;
                            logger.error("Could not send keep alive to Central, keepAliveFailCount = "+keepAliveFailCount);
                            if(keepAliveFailCount > KEEP_ALIVE_FAIL_LIMIT){
                                recoveryManager.doRecovery();
                            }
                        }
                    }
                    return null;
                }
            });

		} else {
			if (logger.isDebugEnabled()) logger.debug("worker waits for recovery");
		}
	}

	@SuppressWarnings("unused") // called by scheduler
	public void logStatistics() {
		if (logger.isDebugEnabled()) {
			logger.debug("InBuffer size: " + getInBufferSize());
			logger.debug("Running task size: " + mapOfRunningTasks.size());
		}
	}

	public String getWorkerUuid() {
		return workerUuid;
	}

	public int getRunningTasksCount() {
		return mapOfRunningTasks.size();
	}

	@Override
	public void onApplicationEvent(final ApplicationEvent applicationEvent) {
		if (applicationEvent instanceof ContextRefreshedEvent && !endOfInit) {
			doStartup();
		} else if (applicationEvent instanceof ContextClosedEvent) {
			doShutdown();
		}
	}

	private void doStartup() {
		new Thread(new Runnable() {
			@Override public void run() {
                SecurityTemplate securityTemplate = new SecurityTemplate(); //TODO- remove this from score
                securityTemplate.invokeSecured(new SecurityTemplate.SecurityTemplateCallback<Void>() {

	                @Override
	                public Void doSecured() {
		                long sleep = initStartUpSleep;
		                boolean shouldRetry = true;
		                while (shouldRetry) {
			                try {
				                workerNodeService.up(workerUuid);
				                shouldRetry = false;
				                logger.info("Worker is up");
			                } catch (Exception ex) {
				                logger.error("Worker failed on start up, will retry in a " + sleep / 1000 + " seconds", ex);
				                try {
					                Thread.sleep(sleep);
				                } catch (InterruptedException iex) {/*do nothing*/}
				                sleep = Math.min(maxStartUpSleep, sleep * 2); // double the sleep time until max 10 minute
			                }
		                }

		                endOfInit = true;
		                //mark that worker is up and its recovery is ended - only now we can start asking for messages from queue
		                up = true;

		                workerConfigurationService.enabled(true);
		                workerNodeService.updateEnvironmentParams(workerUuid,
				                System.getProperty("os.name"),
				                System.getProperty("java.version"),
				                resolveDotNetVersion());
		                return null;
	                }
                });
			}
		}).start();
	}

	private void doShutdown() {
        SecurityTemplate securityTemplate = new SecurityTemplate();//TODO- remove this from score
        securityTemplate.invokeSecured(new SecurityTemplate.SecurityTemplateCallback<Void>() {

	        @Override
	        public Void doSecured() {
		        endOfInit = false;
		        workerConfigurationService.enabled(false);
		        workerNodeService.down(workerUuid);
		        up = false;
		        logger.info("The worker is down");
		        return null;
	        }
        });

    }

	protected String resolveDotNetVersion() {
		File dotNetHome = new File(System.getenv("WINDIR") + "/Microsoft.NET/Framework");
		if (dotNetHome.isDirectory()) {
			File[] versionFolders = dotNetHome.listFiles(new FileFilter() {
				@Override
				public boolean accept(File file) {
					return file.isDirectory() && file.getName().startsWith("v");
				}
			});

			if (!ArrayUtils.isEmpty(versionFolders)) {
				String maxVersion = max(versionFolders, on(File.class).getName()).substring(1);

				return maxVersion.substring(0, 1) + ".x";
			}
		}
		return "N/A";
	}

	public boolean isUp() {
		return up;
	}
}
