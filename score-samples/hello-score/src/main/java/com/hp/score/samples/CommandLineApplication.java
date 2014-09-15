package com.hp.score.samples;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import com.hp.score.api.Score;
import com.hp.score.api.TriggeringProperties;
import com.hp.score.events.EventBus;
import com.hp.score.events.EventConstants;
import com.hp.score.events.ScoreEvent;
import com.hp.score.events.ScoreEventListener;
import com.hp.score.samples.openstack.actions.InputBinding;
import com.hp.score.samples.openstack.actions.OOActionRunner;
import com.hp.score.samples.utility.ReflectionUtility;

import static com.hp.score.samples.openstack.OpenstackCommons.prepareExecutionContext;
import static com.hp.score.samples.openstack.OpenstackCommons.readInput;
import static com.hp.score.samples.openstack.OpenstackCommons.readPredefinedInput;
import static com.hp.score.samples.utility.ReadInputUtility.readIntegerInput;

/**
 * Date: 8/28/2014
 *
 * @author Bonczidai Levente
 */
public class CommandLineApplication {

	public static final String OPENSTACK_FLOWS_PACKAGE = "com.hp.score.samples.openstack";
	private static final String ALLEGRO_BANNER_PATH = "/allegro_banner.txt";

	@Autowired
	private Score score;
	@Autowired
	private EventBus eventBus;
	private List<FlowMetadata> predefinedFlows;
	private volatile int triggeringStatus; //1-running flow, 0-ready for trigger

	public CommandLineApplication() {
		predefinedFlows = new ArrayList<>();
		registerPredefinedExecutionPlans();
	}

	private void registerPredefinedExecutionPlans() {
		registerFlow("display_message", "Simple display message flow", "com.hp.score.samples.DisplayMessageFlow", "displayMessageFlow", "getInputBindings");
		registerFlow("create_server_open_stack", "Create server in OpenStack", OPENSTACK_FLOWS_PACKAGE + ".CreateServerFlow", "createServerFlow", "getInputBindings");
		registerFlow("list_servers_open_stack", "List servers in OpenStack", OPENSTACK_FLOWS_PACKAGE + ".ListServersFlow", "listServersFlow", "getInputBindings");
		registerFlow("delete_server_open_stack", "Delete server in OpenStack", OPENSTACK_FLOWS_PACKAGE + ".DeleteServerFlow", "deleteServerFlow", "getInputBindings");
		registerFlow("validate_server_open_stack", "Validate server exists in OpenStack", OPENSTACK_FLOWS_PACKAGE + ".ValidateServerExistsFlow", "validateServerExistsFlow", "getInputBindings");
		registerFlow("health_check_open_stack", "OpenStack health check", OPENSTACK_FLOWS_PACKAGE + ".OpenStackHealthCheckFlow", "openStackHealthCheckFlow", "getInputBindings");
		registerFlow("health_multi_instance_servers_open_stack", "Create Multi Instance Servers in OpenStack", OPENSTACK_FLOWS_PACKAGE + ".CreateMultiInstanceServersFlow", "createMultiInstanceServersFlow", "getInputBindings");
		registerFlow("parallel_flow_example", "Parallel flow example", OPENSTACK_FLOWS_PACKAGE + ".ParallelFlow", "createParallelFlow", "getInputBindings");
	}

	public void registerFlow(String identifier, String description, String className, String triggeringPropertiesMethodName, String inputBindingsMethodName) {
		FlowMetadata flowMetadata = new FlowMetadata(identifier, description, className, triggeringPropertiesMethodName, inputBindingsMethodName);
		predefinedFlows.add(flowMetadata);
	}

	public static void main(String[] args) {
		CommandLineApplication app = loadApp();
        displaySignature();
		app.registerEventListeners();
		app.start();

	}

	private static void displaySignature() {
		System.out.println(loadBanner(ALLEGRO_BANNER_PATH));
	}

	private static String loadBanner(String relativePath) {
		StringBuilder bannerBuilder = new StringBuilder();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(CommandLineApplication.class.getResourceAsStream(relativePath)))) {
			String line;
			while((line = reader.readLine()) != null) {
				bannerBuilder.append(line);
				bannerBuilder.append("\n");
			}
		} catch(IOException ex) {
			ex.printStackTrace();
		}
		return bannerBuilder.toString();
	}

	private void start() {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
			while(true) {
				if(!isFlowRunning()) {
					displayAvailableFlows(reader);
				} else {
					try {
						Thread.sleep(100);
					} catch(InterruptedException e) {
						e.printStackTrace();
					}
				}
			}
		} catch(IOException ex) {
			ex.printStackTrace();
		}
	}

	private boolean isFlowRunning() {
		return triggeringStatus == 1;
	}

	private void displayAvailableFlows(BufferedReader reader) {
		int executionPlanNumber = listPredefinedFlows(reader);
		try {
			runPredefinedFlows(executionPlanNumber, reader);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void runPredefinedFlows(int executionPlanNumber, BufferedReader reader) throws Exception {
		FlowMetadata flowMetadata = predefinedFlows.get(executionPlanNumber);
		runFlow(flowMetadata.getClassName(), flowMetadata.getTriggeringPropertiesMethodName(),
				flowMetadata.getInputBindingsMethodName(), reader);
	}

	private int listPredefinedFlows(BufferedReader reader) {
		System.out.println("Available flows");
		for (FlowMetadata flowMetadata : predefinedFlows) {
			System.out.println(predefinedFlows.indexOf(flowMetadata) + " - " + flowMetadata.getDescription());
		}
		return readIntegerInput(reader, "Insert the flow number");
	}

	private void runFlow(String className, String triggeringPropertiesMethodName, String inputBindingMethodName, BufferedReader reader) throws Exception {
		List<InputBinding> bindings = prepareInputBindings(className, inputBindingMethodName);
		manageBindings(bindings, reader);
		TriggeringProperties triggeringProperties = prepareTriggeringProperties(className, triggeringPropertiesMethodName, bindings);
		setTriggeringStatus(1);
		score.trigger(triggeringProperties);
	}

	private static void manageBindings(List<InputBinding> bindings, BufferedReader reader) {
		for (InputBinding inputBinding : bindings) {
			String input = null;
			boolean validValueEntered = false;
			while (!validValueEntered) {
				if (inputBinding.hasDefaultValue()) {
					input = readPredefinedInput(reader, inputBinding.getDescription(), inputBinding.getValue()).trim();
					validValueEntered = true;
				} else {
					input = readInput(reader, inputBinding.getDescription()).trim();
					validValueEntered = !input.isEmpty();
				}
			}
			//if input is empty use the default value already set, otherwise use input
			if (!(input==null || input.isEmpty())) {
				inputBinding.setValue(input);
			}
		}
	}

	private static List<InputBinding> prepareInputBindings(String className, String methodName) throws Exception {
		Object returnValue = ReflectionUtility.invokeMethodByName(className, methodName);
		try {
			@SuppressWarnings("unchecked")
			List<InputBinding> bindings = (List<InputBinding>) returnValue;
			return bindings;
		}
		catch (ClassCastException ex) {
			throw new Exception("Exception occurred during input binding extraction");
		}
	}

	private static TriggeringProperties prepareTriggeringProperties(String className, String methodName, List<InputBinding> bindings) throws Exception {
		Object returnValue = ReflectionUtility.invokeMethodByName(className, methodName);
		if (returnValue instanceof TriggeringProperties) {
			TriggeringProperties triggeringProperties = (TriggeringProperties) returnValue;
			//merge the flow inputs with the initial context (flow may have default values in context)
			Map<String, Serializable> context = new HashMap<>();
			context.putAll(triggeringProperties.getContext());
			context.putAll(prepareExecutionContext(bindings));
			triggeringProperties.setContext(context);
			return triggeringProperties;
		}
		throw new Exception("Exception occurred during TriggeringProperties extraction");
	}

	private static CommandLineApplication loadApp() {
		ApplicationContext context = new ClassPathXmlApplicationContext("/META-INF/spring/commandLineApplicationContext.xml");
		@SuppressWarnings("all")
		CommandLineApplication app = context.getBean(CommandLineApplication.class);
		return app;
	}

	private void registerEventListeners() {
		registerOOActionRunnerEventListener();
		registerExceptionEventListener();
		registerScoreEventListener();
	}

	private void registerOOActionRunnerEventListener() {
		Set<String> handlerTypes = new HashSet<>();
		handlerTypes.add(OOActionRunner.ACTION_RUNTIME_EVENT_TYPE);
		eventBus.subscribe(new ScoreEventListener() {
			@Override
			public void onEvent(ScoreEvent event) {
				logListenerEvent(event, true);
			}
		}, handlerTypes);
	}

	private void registerExceptionEventListener() {
		Set<String> handlerTypes = new HashSet<>();
		handlerTypes.add(OOActionRunner.ACTION_EXCEPTION_EVENT_TYPE);
		eventBus.subscribe(new ScoreEventListener() {
			@Override
			public void onEvent(ScoreEvent event) {
				logListenerEvent(event, true);
			}
		}, handlerTypes);
	}

	private void registerScoreEventListener() {
		Set<String> handlerTypes = new HashSet<>();
		handlerTypes.add(EventConstants.SCORE_FINISHED_EVENT);
		handlerTypes.add(EventConstants.SCORE_ERROR_EVENT);
		handlerTypes.add(EventConstants.SCORE_FAILURE_EVENT);
		eventBus.subscribe(new ScoreEventListener() {
			@Override
			public void onEvent(ScoreEvent event) {
				logListenerEvent(event, false);
				setTriggeringStatus(0);
			}
		}, handlerTypes);
	}

	private void setTriggeringStatus(int status) {
		triggeringStatus = status;
	}

	private void logListenerEvent(ScoreEvent event, boolean displayData) {
		String message;
		if (displayData) {
			message = "Event " + event.getEventType() + " occurred: " + event.getData();
		} else {
			message = "Event " + event.getEventType() + " occurred";
		}
		System.out.println(message);
	}

	private static class FlowMetadata {
		private String identifier;
		private String description;
		private String className;
		private String triggeringPropertiesMethodName;
		private String inputBindingsMethodName;

		private FlowMetadata(String identifier, String description, String className, String triggeringPropertiesMethodName, String inputBindingsMethodName) {
			this.identifier = identifier;
			this.description = description;
			this.className = className;
			this.triggeringPropertiesMethodName = triggeringPropertiesMethodName;
			this.inputBindingsMethodName = inputBindingsMethodName;
		}

		public String getDescription() {
			return description;
		}

		@SuppressWarnings("unused")
		public String getIdentifier() {
			return identifier;
		}

		public String getClassName() {
			return className;
		}

		public String getTriggeringPropertiesMethodName() {
			return triggeringPropertiesMethodName;
		}

		public String getInputBindingsMethodName() {
			return inputBindingsMethodName;
		}
	}

}
