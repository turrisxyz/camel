/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.processor;

import java.util.List;

import org.apache.camel.AsyncCallback;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePropertyKey;
import org.apache.camel.ExtendedExchange;
import org.apache.camel.Predicate;
import org.apache.camel.Processor;
import org.apache.camel.RollbackExchangeException;
import org.apache.camel.Traceable;
import org.apache.camel.spi.IdAware;
import org.apache.camel.spi.RouteIdAware;
import org.apache.camel.support.EventHelper;
import org.apache.camel.support.ExchangeHelper;
import org.apache.camel.support.processor.DelegateAsyncProcessor;
import org.apache.camel.util.ObjectHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A processor which catches exceptions.
 */
public class CatchProcessor extends DelegateAsyncProcessor implements Traceable, IdAware, RouteIdAware {

    private static final Logger LOG = LoggerFactory.getLogger(CatchProcessor.class);

    private String id;
    private String routeId;
    private final List<Class<? extends Throwable>> exceptions;
    private final Predicate onWhen;

    public CatchProcessor(List<Class<? extends Throwable>> exceptions, Processor processor, Predicate onWhen,
                          Predicate handled) {
        super(processor);
        this.exceptions = exceptions;
        this.onWhen = onWhen;
    }

    @Override
    public String toString() {
        return id;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public void setId(String id) {
        this.id = id;
    }

    @Override
    public String getRouteId() {
        return routeId;
    }

    @Override
    public void setRouteId(String routeId) {
        this.routeId = routeId;
    }

    @Override
    public String getTraceLabel() {
        return "catch";
    }

    @Override
    public boolean process(final Exchange exchange, final AsyncCallback callback) {
        final Exception e = exchange.getException();
        Throwable caught = catches(exchange, e);
        // If a previous catch clause handled the exception or if this clause does not match, exit
        if (exchange.getProperty(ExchangePropertyKey.EXCEPTION_HANDLED) != null || caught == null) {
            callback.done(true);
            return true;
        }
        if (LOG.isTraceEnabled()) {
            LOG.trace("This CatchProcessor catches the exception: {} caused by: {}", caught.getClass().getName(),
                    e.getMessage());
        }

        // must remember some properties which we cannot use during doCatch processing
        ExtendedExchange ee = (ExtendedExchange) exchange;
        final boolean stop = ee.isRouteStop();
        ee.setRouteStop(false);
        final boolean rollbackOnly = ee.isRollbackOnly();
        ee.setRollbackOnly(false);
        final boolean rollbackOnlyLast = ee.isRollbackOnlyLast();
        ee.setRollbackOnlyLast(false);

        // store the last to endpoint as the failure endpoint
        if (exchange.getProperty(ExchangePropertyKey.FAILURE_ENDPOINT) == null) {
            exchange.setProperty(ExchangePropertyKey.FAILURE_ENDPOINT, exchange.getProperty(ExchangePropertyKey.TO_ENDPOINT));
        }
        // give the rest of the pipeline another chance
        exchange.setProperty(ExchangePropertyKey.EXCEPTION_HANDLED, true);
        exchange.setProperty(ExchangePropertyKey.EXCEPTION_CAUGHT, e);
        exchange.setException(null);
        // and we should not be regarded as exhausted as we are in a try .. catch block
        exchange.adapt(ExtendedExchange.class).setRedeliveryExhausted(false);

        if (LOG.isDebugEnabled()) {
            LOG.debug("The exception is handled for the exception: {} caused by: {}",
                    e.getClass().getName(), e.getMessage());
        }

        // emit event that the failure is being handled
        EventHelper.notifyExchangeFailureHandling(exchange.getContext(), exchange, processor, false, null);

        boolean sync = processor.process(exchange, new AsyncCallback() {
            public void done(boolean doneSync) {
                // emit event that the failure was handled
                EventHelper.notifyExchangeFailureHandled(exchange.getContext(), exchange, processor, false, null);

                // always clear redelivery exhausted in a catch clause
                exchange.adapt(ExtendedExchange.class).setRedeliveryExhausted(false);

                if (rollbackOnly || rollbackOnlyLast || stop) {
                    exchange.setRouteStop(stop);
                    exchange.setRollbackOnly(rollbackOnly);
                    exchange.setRollbackOnlyLast(rollbackOnlyLast);
                    // special for rollback as we need to restore that a rollback was triggered
                    if (e instanceof RollbackExchangeException) {
                        exchange.setException(e);
                    }
                }

                if (!doneSync) {
                    // signal callback to continue routing async
                    ExchangeHelper.prepareOutToIn(exchange);
                }

                callback.done(doneSync);
            }
        });

        return sync;
    }

    /**
     * Returns with the exception that is caught by this processor.
     *
     * This method traverses exception causes, so sometimes the exception returned from this method might be one of
     * causes of the parameter passed.
     *
     * @param  exchange  the current exchange
     * @param  exception the thrown exception
     * @return           Throwable that this processor catches. <tt>null</tt> if nothing matches.
     */
    protected Throwable catches(Exchange exchange, Throwable exception) {
        // use the exception iterator to walk the caused by hierarchy
        for (final Throwable e : ObjectHelper.createExceptionIterable(exception)) {
            // see if we catch this type
            for (final Class<?> type : exceptions) {
                if (type.isInstance(e) && matchesWhen(exchange)) {
                    return e;
                }
            }
        }

        // not found
        return null;
    }

    public List<Class<? extends Throwable>> getExceptions() {
        return exceptions;
    }

    /**
     * Strategy method for matching the exception type with the current exchange.
     * <p/>
     * This default implementation will match as:
     * <ul>
     * <li>Always true if no when predicate on the exception type
     * <li>Otherwise the when predicate is matches against the current exchange
     * </ul>
     *
     * @param  exchange the current {@link org.apache.camel.Exchange}
     * @return          <tt>true</tt> if matched, <tt>false</tt> otherwise.
     */
    protected boolean matchesWhen(Exchange exchange) {
        if (onWhen == null) {
            // if no predicate then it's always a match
            return true;
        }
        return onWhen.matches(exchange);
    }
}
