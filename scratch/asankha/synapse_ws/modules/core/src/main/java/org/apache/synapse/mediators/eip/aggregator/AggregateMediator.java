/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *   * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.apache.synapse.mediators.eip.aggregator;

import org.apache.axiom.om.xpath.AXIOMXPath;
import org.apache.axiom.soap.SOAP11Constants;
import org.apache.axiom.soap.SOAP12Constants;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseConstants;
import org.apache.synapse.mediators.AbstractMediator;
import org.apache.synapse.mediators.eip.EIPUtils;
import org.apache.synapse.mediators.eip.EIPConstants;
import org.apache.synapse.mediators.base.SequenceMediator;
import org.jaxen.JaxenException;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Collections;

/**
 * Aggregate a number of messages that are determined to be for a particular group, and combine
 * them to form a single message which is then processed through the 'onComplete' sequence. Thus
 * an aggregator acts like a filter, and may look at a correlation XPath expression to select
 * messages for aggregation - or look at messageSequence number properties for aggregation or
 * let any other (i.e. non aggregatable) messages flow through
 * An instance of this mediator will register with a Timer to be notified after a specified timeout,
 * so that aggregations that never would complete could be timed out and cleared from memory and
 * any fault conditions handled
 */
public class AggregateMediator extends AbstractMediator {

    private static final Log log = LogFactory.getLog(AggregateMediator.class);
    private static final Log trace = LogFactory.getLog(SynapseConstants.TRACE_LOGGER);

    /** The duration as a number of milliseconds for this aggregation to complete */
    private long completionTimeoutMillis = 0;
    /** The minimum number of messages required to complete aggregation */
    private int minMessagesToComplete = -1;
    /** The maximum number of messages required to complete aggregation */
    private int maxMessagesToComplete = -1;

    /**
     * XPath that specifies a correlation expression that can be used to combine messages. An
     * example maybe //department@id="11"
     */
    private AXIOMXPath correlateExpression = null;
    /**
     * An XPath expression that may specify a selected element to be aggregated from a group of
     * messages to create the aggregated message
     * e.g. //getQuote/return would pick up and aggregate the //getQuote/return elements from a
     * bunch of matching messages into one aggregated message
     */
    private AXIOMXPath aggregationExpression = null;

    /** This holds the reference sequence name of the */
    private String onCompleteSequenceRef = null;
    /** Inline sequence definition holder that holds the onComplete sequence */
    private SequenceMediator onCompleteSequence = null;

    /** The active aggregates currently being processd */
    private Map<String, Aggregate> activeAggregates =
        Collections.synchronizedMap(new HashMap<String, Aggregate>());

    public AggregateMediator() {
        try {
            aggregationExpression = new AXIOMXPath("s11:Body/child::*[position()=1] | " +
                "s12:Body/child::*[position()=1]");
            aggregationExpression.addNamespace("s11", SOAP11Constants.SOAP_ENVELOPE_NAMESPACE_URI);
            aggregationExpression.addNamespace("s12", SOAP12Constants.SOAP_ENVELOPE_NAMESPACE_URI);
        } catch (JaxenException e) {
            if (log.isDebugEnabled()) {
                handleException("Unable to set the default " +
                    "aggregationExpression for the aggregation", e, null);
            }
        }
    }

    /**
     * Aggregate messages flowing through this mediator according to the correlation criteria
     * and the aggregation algorithm specified to it
     *
     * @param synCtx - MessageContext to be mediated and aggregated
     * @return boolean true if the complete condition for the particular aggregate is validated
     */
    public boolean mediate(MessageContext synCtx) {

        boolean traceOn = isTraceOn(synCtx);
        boolean traceOrDebugOn = isTraceOrDebugOn(traceOn);

        if (traceOrDebugOn) {
            traceOrDebug(traceOn, "Start : Aggregate mediator");

            if (traceOn && trace.isTraceEnabled()) {
                trace.trace("Message : " + synCtx.getEnvelope());
            }
        }

        try {
            Aggregate aggregate = null;

            // if a correlateExpression is provided and there is a coresponding
            // element in the current message prepare to correlate the messages on that
            if (correlateExpression != null
                && correlateExpression.evaluate(synCtx.getEnvelope()) != null) {

                if (activeAggregates.containsKey(correlateExpression.toString())) {
                    aggregate = activeAggregates.get(correlateExpression.toString());

                } else {
                    if (traceOrDebugOn) {
                        traceOrDebug(traceOn, "Creating new Aggregator - expires in : " +
                            (completionTimeoutMillis / 1000) + "secs");
                    }

                    aggregate = new Aggregate(
                        correlateExpression.toString(),
                        completionTimeoutMillis,
                        minMessagesToComplete,
                        maxMessagesToComplete, this);
                    synCtx.getConfiguration().getSynapseTimer().
                        schedule(aggregate, completionTimeoutMillis);
                    activeAggregates.put(correlateExpression.toString(), aggregate);
                }

            } else if (synCtx.getProperty(EIPConstants.AGGREGATE_CORRELATION) != null) {
                // if the correlattion cannot be found using the correlateExpression then
                // try the default which is through the AGGREGATE_CORRELATION message property
                // which is the unique original message id of a split or iterate operation and
                // which thus can be used to uniquely group messages into aggregates

                Object o = synCtx.getProperty(EIPConstants.AGGREGATE_CORRELATION);
                String correlation = null;

                if (o != null && o instanceof String) {
                    correlation = (String) o;

                    if (activeAggregates.containsKey(correlation)) {
                        aggregate = activeAggregates.get(correlation);

                    } else {
                        if (traceOrDebugOn) {
                            traceOrDebug(traceOn, "Creating new Aggregator - expires in : " +
                                (completionTimeoutMillis / 1000) + "secs");
                        }
                        
                        aggregate = new Aggregate(
                            correlation,
                            completionTimeoutMillis,
                            minMessagesToComplete,
                            maxMessagesToComplete, this);
                        synCtx.getConfiguration().getSynapseTimer().
                            schedule(aggregate, completionTimeoutMillis);
                        activeAggregates.put(correlation, aggregate);
                    }

                } else {
                    if (traceOrDebugOn) {
                        traceOrDebug(traceOn, "Unable to find aggrgation correlation property");
                    }
                    return true;
                }
            } else {
                if (traceOrDebugOn) {
                    traceOrDebug(traceOn, "Unable to find aggrgation correlation XPath or property");
                }
                return true;
            }

            // if there is an aggregate continue on aggregation
            if (aggregate != null) {
                boolean collected = aggregate.addMessage(synCtx);
                if (traceOrDebugOn) {
                    if (collected) {
                        traceOrDebug(traceOn, "Collected a message during aggregation");
                        if (traceOn && trace.isTraceEnabled()) {
                            trace.trace("Collected message : " + synCtx);
                        }
                    }
                }
                
                // check the completeness of the aggregate and if completed aggregate the messages
                // if not completed return false and block the message sequence till it completes

                if (aggregate.isComplete(traceOn, traceOrDebugOn, trace, log)) {
                    if (traceOrDebugOn) {
                        traceOrDebug(traceOn, "Aggregation completed - invoking onComplete");
                    }
                    completeAggregate(aggregate);
                    
                    if (traceOrDebugOn) {
                        traceOrDebug(traceOn, "End : Aggregate mediator");
                    }
                    return true;
                }

            } else {
                // if the aggregation correlation cannot be found then continue the message on the
                // normal path by returning true

                if (traceOrDebugOn) {
                    traceOrDebug(traceOn, "Unable to find an aggregate for this message - skip");
                }
                return true;
            }

        } catch (JaxenException e) {
            handleException("Unable to execute the XPATH over the message", e, synCtx);
        }

        if (traceOrDebugOn) {
            traceOrDebug(traceOn, "End : Aggregate mediator");
        }

        return false;
    }

    /**
     * Invoked by the Aggregate objects that are timed out, to signal timeout/completion of
     * itself
     * @param aggregate the timed out Aggregate that holds collected messages and properties
     */
    public void completeAggregate(Aggregate aggregate) {

        if (log.isDebugEnabled()) {
            log.debug("Aggregation completed or timed out");
        }

        // cancel the timer
        aggregate.cancel();

        MessageContext newSynCtx = getAggregatedMessage(aggregate);
        if (newSynCtx == null) {
            log.warn("An aggregation of messages timed out with no aggregated messages", null);
            return;
        }

        activeAggregates.remove(aggregate);

        if ((correlateExpression != null &&
            !correlateExpression.toString().equals(aggregate.getCorrelation())) ||
            correlateExpression == null) {

            if (onCompleteSequence != null) {
                onCompleteSequence.mediate(newSynCtx);

            } else if (onCompleteSequenceRef != null
                && newSynCtx.getSequence(onCompleteSequenceRef) != null) {
                newSynCtx.getSequence(onCompleteSequenceRef).mediate(newSynCtx);

            } else {
                handleException("Unable to find the sequence for the mediation " +
                    "of the aggregated message", newSynCtx);
            }
        }
    }

    /**
     * Get the aggregated message from the specified Aggregate instance
     *
     * @param aggregate the Aggregate object that holds collected messages and properties of the
     * aggregation
     * @return the aggregated message context
     */
    private MessageContext getAggregatedMessage(Aggregate aggregate) {

        MessageContext newCtx = null;
        Iterator<MessageContext> itr = aggregate.getMessages().iterator();

        while (itr.hasNext()) {
            MessageContext synCtx = itr.next();
            if (newCtx == null) {
                newCtx = synCtx;

                if (log.isDebugEnabled()) {
                    log.debug("Generating Aggregated message from : " + newCtx.getEnvelope());
                }

            } else {
                try {
                    if (log.isDebugEnabled()) {
                        log.debug("Merging message : " + synCtx.getEnvelope() + " using XPath : " +
                            aggregationExpression);
                    }

                    EIPUtils.enrichEnvelope(
                        newCtx.getEnvelope(), synCtx.getEnvelope(), aggregationExpression);

                    if (log.isDebugEnabled()) {
                        log.debug("Merged result : " + newCtx.getEnvelope());    
                    }

                } catch (JaxenException e) {
                    handleException("Error merging aggregation results using XPath : " +
                        aggregationExpression.toString(), e, synCtx);
                }
            }
        }
        return newCtx;
    }

    public AXIOMXPath getCorrelateExpression() {
        return correlateExpression;
    }

    public void setCorrelateExpression(AXIOMXPath correlateExpression) {
        this.correlateExpression = correlateExpression;
    }

    public long getCompletionTimeoutMillis() {
        return completionTimeoutMillis;
    }

    public void setCompletionTimeoutMillis(long completionTimeoutMillis) {
        this.completionTimeoutMillis = completionTimeoutMillis;
    }

    public int getMinMessagesToComplete() {
        return minMessagesToComplete;
    }

    public void setMinMessagesToComplete(int minMessagesToComplete) {
        this.minMessagesToComplete = minMessagesToComplete;
    }

    public int getMaxMessagesToComplete() {
        return maxMessagesToComplete;
    }

    public void setMaxMessagesToComplete(int maxMessagesToComplete) {
        this.maxMessagesToComplete = maxMessagesToComplete;
    }

    public AXIOMXPath getAggregationExpression() {
        return aggregationExpression;
    }

    public void setAggregationExpression(AXIOMXPath aggregationExpression) {
        this.aggregationExpression = aggregationExpression;
    }

    public String getOnCompleteSequenceRef() {
        return onCompleteSequenceRef;
    }

    public void setOnCompleteSequenceRef(String onCompleteSequenceRef) {
        this.onCompleteSequenceRef = onCompleteSequenceRef;
    }

    public SequenceMediator getOnCompleteSequence() {
        return onCompleteSequence;
    }

    public void setOnCompleteSequence(SequenceMediator onCompleteSequence) {
        this.onCompleteSequence = onCompleteSequence;
    }

    public Map getActiveAggregates() {
        return activeAggregates;
    }
}
