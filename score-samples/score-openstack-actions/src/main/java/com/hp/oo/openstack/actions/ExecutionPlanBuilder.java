package com.hp.oo.openstack.actions;

import com.hp.score.api.ControlActionMetadata;
import com.hp.score.api.ExecutionPlan;
import com.hp.score.api.ExecutionStep;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Date: 7/22/2014
 *
 * @author Bonczidai Levente
 */
public class ExecutionPlanBuilder {
	public static final String ACTION_CLASS_KEY = "className";
	public static final String ACTION_METHOD_KEY = "methodName";
	private static Long stepCount;

	private ExecutionPlan executionPlan;

	public ExecutionPlanBuilder() {
		executionPlan = new ExecutionPlan();
		executionPlan.setFlowUuid(UUID.randomUUID().toString());
		stepCount = 0L;
		executionPlan.setBeginStep(stepCount);
	}

	public ExecutionPlan getExecutionPlan() {
		return executionPlan;
	}

	public Long addStep(String actionClassName,
						String actionMethodName,
						String nextStepID) {
		ExecutionStep step = new ExecutionStep(stepCount++);

		step.setAction(new ControlActionMetadata("com.hp.oo.openstack.actions.OOActionRunner", "runWithServices"));
		Map<String, String> actionData = new HashMap<>(2);
		//put the actual action class name and method name
		actionData.put(ACTION_CLASS_KEY, actionClassName);
		actionData.put(ACTION_METHOD_KEY, actionMethodName);
		step.setActionData(actionData);

		step.setNavigation(new ControlActionMetadata("com.hp.oo.openstack.actions.OOActionRunner", "navigate"));
		Map<String, String> navigationData = new HashMap<>(1);
		navigationData.put("nextStep", nextStepID);
		step.setNavigationData(navigationData);

		step.setSplitStep(false);

		executionPlan.addStep(step);

		return step.getExecStepId();
	}

	@SuppressWarnings("unused")
	public void setBeginStep(Long beginStepId)
	{
		executionPlan.setBeginStep(beginStepId);
	}
}