package com.netflix.zuul;

import com.netflix.zuul.context.SessionCleaner;
import com.netflix.zuul.context.SessionContext;
import com.netflix.zuul.context.SessionContextDecorator;
import com.netflix.zuul.context.SessionContextFactory;
import com.netflix.zuul.message.ZuulMessage;
import com.netflix.zuul.message.http.HttpResponseMessage;
import com.netflix.zuul.message.http.HttpResponseMessageImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;

import javax.annotation.Nullable;
import javax.inject.Inject;

/**
 * The main processing class for Zuul.
 *
 * 1. Translates the inbound native request (ie. HttpServletRequest, or rxnetty HttpServerRequest) into a zuul HttpRequestMessage.
 * 2. Builds the filter chain and passes the request through it.
 * 3. Writes out the HttpResponseMessage to the native response object.
 *
 * User: michaels@netflix.com
 * Date: 8/3/15
 * Time: 11:38 AM
 */
public class ZuulHttpProcessor<I,O>
{
    private static final Logger LOG = LoggerFactory.getLogger(ZuulHttpProcessor.class);

    private FilterProcessor filterProcessor;
    private SessionContextFactory contextFactory;
    private SessionContextDecorator decorator;
    private SessionCleaner sessionCleaner;
    private RequestCompleteHandler requestCompleteHandler;

    /** Ensure that this is initialized. */
    private FilterFileManager filterManager;


    @Inject
    public ZuulHttpProcessor(FilterProcessor filterProcessor, SessionContextFactory contextFactory,
                             @Nullable SessionContextDecorator decorator,
                             @Nullable RequestCompleteHandler requestCompleteHandler,
                             FilterFileManager filterManager, SessionCleaner sessionCleaner)
    {
        this.filterProcessor = filterProcessor;
        this.contextFactory = contextFactory;
        this.decorator = decorator;
        this.requestCompleteHandler = requestCompleteHandler;
        this.filterManager = filterManager;
        this.sessionCleaner = sessionCleaner;
    }

    public Observable<ZuulMessage> process(final I nativeRequest, final O nativeResponse)
    {
        // Setup the context for this request.
        final SessionContext context;

        // Optionally decorate the context.
        if (decorator == null) {
            context = new SessionContext();
        } else {
            context = decorator.decorate(new SessionContext());
        }

        // Build a ZuulMessage from the netty request.
        final ZuulMessage request = contextFactory.create(context, nativeRequest);

        // Start timing the request.
        request.getContext().getTimings().getRequest().start();

        // Create initial chain.
        Observable<ZuulMessage> chain = Observable.just(request);

        /*
         * Delegate all of the filter application logic to {@link FilterProcessor}.
         * This work is some combination of synchronous and asynchronous.
         */
        chain = filterProcessor.applyInboundFilters(chain);
        chain = filterProcessor.applyEndpointFilter(chain);
        chain = filterProcessor.applyOutboundFilters(chain);

        return chain
                .flatMap(msg -> {
                    // Wrap this in a try/catch because we need to ensure no exception stops the observable, as
                    // we need the following doOnNext to always run - as it records metrics.
                    try {
                        // Write out the response.
                        return contextFactory.write(msg, nativeResponse);
                    }
                    catch (Exception e) {
                        LOG.error("Error in writing response! request=" + request.getInfoForLogging(), e);

                        // Generate a default error response to be sent to client.
                        return Observable.just(new HttpResponseMessageImpl(context, ((HttpResponseMessage) msg).getOutboundRequest(), 500));
                    }
                    finally {
                        // End the timing.
                        msg.getContext().getTimings().getRequest().end();
                    }
                })
                .doOnError(e -> {
                    LOG.error("Unexpected error in filter chain! request=" + request.getInfoForLogging(), e);
                })
                .doOnNext(msg -> {
                    // Notify requestComplete listener if configured.
                    try {
                        if (requestCompleteHandler != null)
                            requestCompleteHandler.handle((HttpResponseMessage) msg);
                    }
                    catch (Exception e) {
                        LOG.error("Error in RequestCompleteHandler.", e);
                    }
                })
                .finallyDo(() -> {
                    // Cleanup any resources related to this request/response.
                    sessionCleaner.cleanup(context);
                });
    }
}