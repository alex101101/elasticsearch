/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.reindex.remote;

import org.apache.http.HttpEntity;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.Version;
import org.elasticsearch.action.bulk.BackoffPolicy;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.ResponseListener;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.common.ParseFieldMatcher;
import org.elasticsearch.common.ParseFieldMatcherSupplier;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.AbstractRunnable;
import org.elasticsearch.common.xcontent.XContent;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.reindex.ScrollableHitSource;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.threadpool.ThreadPool;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import static java.util.Collections.emptyMap;
import static org.elasticsearch.common.unit.TimeValue.timeValueMillis;
import static org.elasticsearch.common.unit.TimeValue.timeValueNanos;
import static org.elasticsearch.index.reindex.remote.RemoteRequestBuilders.initialSearchEntity;
import static org.elasticsearch.index.reindex.remote.RemoteRequestBuilders.initialSearchParams;
import static org.elasticsearch.index.reindex.remote.RemoteRequestBuilders.initialSearchPath;
import static org.elasticsearch.index.reindex.remote.RemoteRequestBuilders.scrollEntity;
import static org.elasticsearch.index.reindex.remote.RemoteRequestBuilders.scrollParams;
import static org.elasticsearch.index.reindex.remote.RemoteRequestBuilders.scrollPath;
import static org.elasticsearch.index.reindex.remote.RemoteResponseParsers.MAIN_ACTION_PARSER;
import static org.elasticsearch.index.reindex.remote.RemoteResponseParsers.RESPONSE_PARSER;

public class RemoteScrollableHitSource extends ScrollableHitSource {
    private final RestClient client;
    private final BytesReference query;
    private final SearchRequest searchRequest;
    Version remoteVersion;

    public RemoteScrollableHitSource(ESLogger logger, BackoffPolicy backoffPolicy, ThreadPool threadPool, Runnable countSearchRetry,
            Consumer<Exception> fail, RestClient client, BytesReference query, SearchRequest searchRequest) {
        super(logger, backoffPolicy, threadPool, countSearchRetry, fail);
        this.query = query;
        this.searchRequest = searchRequest;
        this.client = client;
    }

    @Override
    public void close() {
        try {
            client.close();
        } catch (IOException e) {
            fail.accept(new IOException("couldn't close the remote connection", e));
        }
    }

    @Override
    protected void doStart(Consumer<? super Response> onResponse) {
        lookupRemoteVersion(version -> {
            remoteVersion = version;
            execute("POST", initialSearchPath(searchRequest), initialSearchParams(searchRequest, version),
                    initialSearchEntity(query), RESPONSE_PARSER, r -> onStartResponse(onResponse, r));
        });
    }

    void lookupRemoteVersion(Consumer<Version> onVersion) {
        execute("GET", "", emptyMap(), null, MAIN_ACTION_PARSER, onVersion);
        
    }

    private void onStartResponse(Consumer<? super Response> onResponse, Response response) {
        if (Strings.hasLength(response.getScrollId()) && response.getHits().isEmpty()) {
            logger.debug("First response looks like a scan response. Jumping right to the second. scroll=[{}]", response.getScrollId());
            doStartNextScroll(response.getScrollId(), timeValueMillis(0), onResponse);
        } else {
            onResponse.accept(response);
        }
    }

    @Override
    protected void doStartNextScroll(String scrollId, TimeValue extraKeepAlive, Consumer<? super Response> onResponse) {
        execute("POST", scrollPath(), scrollParams(timeValueNanos(searchRequest.scroll().keepAlive().nanos() + extraKeepAlive.nanos())),
                scrollEntity(scrollId), RESPONSE_PARSER, onResponse);
    }

    @Override
    protected void clearScroll(String scrollId) {
        // Need to throw out response....
        client.performRequest("DELETE", scrollPath(), emptyMap(), scrollEntity(scrollId), new ResponseListener() {
            @Override
            public void onSuccess(org.elasticsearch.client.Response response) {
                logger.debug("Successfully cleared [{}]", scrollId);
            }

            @Override
            public void onFailure(Exception t) {
                logger.warn("Failed to clear scroll [{}]", t, scrollId);
            }
        });
    }

    private <T> void execute(String method, String uri, Map<String, String> params, HttpEntity entity,
            BiFunction<XContentParser, ParseFieldMatcherSupplier, T> parser, Consumer<? super T> listener) {
        class RetryHelper extends AbstractRunnable {
            private final Iterator<TimeValue> retries = backoffPolicy.iterator();

            @Override
            protected void doRun() throws Exception {
                client.performRequest(method, uri, params, entity, new ResponseListener() {
                    @Override
                    public void onSuccess(org.elasticsearch.client.Response response) {
                        T parsedResponse;
                        try {
                            InputStream content = response.getEntity().getContent();
                            XContent xContent = XContentFactory.xContentType(content).xContent();
                            try(XContentParser xContentParser = xContent.createParser(content)) {
                                parsedResponse = parser.apply(xContentParser, () -> ParseFieldMatcher.STRICT);
                            }
                        } catch (IOException e) {
                            throw new ElasticsearchException("Error deserializing response", e);
                        }
                        listener.accept(parsedResponse);
                    }

                    @Override
                    public void onFailure(Exception e) {
                        if (e instanceof ResponseException) {
                            ResponseException re = (ResponseException) e;
                            if (RestStatus.TOO_MANY_REQUESTS.getStatus() == re.getResponse().getStatusLine().getStatusCode()) {
                                if (retries.hasNext()) {
                                    TimeValue delay = retries.next();
                                    logger.trace("retrying rejected search after [{}]", e, delay);
                                    countSearchRetry.run();
                                    threadPool.schedule(delay, ThreadPool.Names.SAME, RetryHelper.this);
                                    return;
                                }
                            }
                        }
                        fail.accept(e);
                    }
                });
            }

            @Override
            public void onFailure(Exception t) {
                fail.accept(t);
            }
        }
        new RetryHelper().run();
    }
}
