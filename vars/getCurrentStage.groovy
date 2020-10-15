#!/usr/bin/env groovy

import hudson.model.Action;

import org.jenkinsci.plugins.workflow.graph.FlowNode
import org.jenkinsci.plugins.workflow.cps.nodes.StepStartNode
import org.jenkinsci.plugins.workflow.actions.LabelAction


def getStage(currentBuild){
    def build = currentBuild.getRawBuild()
    def execution = build.getExecution()
    def executionHeads = execution.getCurrentHeads()
    def stepStartNode = getStepStartNode(executionHeads)

    if(stepStartNode){
        return stepStartNode.getDisplayName()
    }
}

def getStepStartNode(List<FlowNode> flowNodes){
    def currentFlowNode = null
    def labelAction = null

    for (FlowNode flowNode: flowNodes){
        currentFlowNode = flowNode
        labelAction = false

        if (flowNode instanceof StepStartNode){
            labelAction = hasLabelAction(flowNode)
        }

        if (labelAction){
            return flowNode
        }
    }

    if (currentFlowNode == null) {
        return null
    }

    return getStepStartNode(currentFlowNode.getParents())
}

def hasLabelAction(FlowNode flowNode){
    def actions = flowNode.getActions()

    for (Action action: actions){
        if (action instanceof LabelAction) {
            return true
        }
    }

    return false
}

def call() {
    return getStage(currentBuild)
}
