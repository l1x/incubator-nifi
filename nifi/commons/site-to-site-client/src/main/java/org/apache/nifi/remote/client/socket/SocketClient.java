/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.remote.client.socket;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;

import org.apache.nifi.events.EventReporter;
import org.apache.nifi.remote.RemoteDestination;
import org.apache.nifi.remote.Transaction;
import org.apache.nifi.remote.TransferDirection;
import org.apache.nifi.remote.client.SiteToSiteClient;
import org.apache.nifi.remote.exception.HandshakeException;
import org.apache.nifi.remote.exception.PortNotRunningException;
import org.apache.nifi.remote.exception.ProtocolException;
import org.apache.nifi.remote.exception.UnknownPortException;
import org.apache.nifi.remote.protocol.DataPacket;
import org.apache.nifi.util.ObjectHolder;

public class SocketClient implements SiteToSiteClient {
	private final EndpointConnectionStatePool pool;
	private final boolean compress;
	private final String portName;
	private final long penalizationNanos;
	private volatile String portIdentifier;
	
	private SocketClient(final Builder builder) {
		pool = new EndpointConnectionStatePool(builder.url, (int) TimeUnit.NANOSECONDS.toMillis(builder.timeoutNanos), 
				builder.sslContext, builder.eventReporter, builder.peerPersistenceFile);
		
		this.compress = builder.useCompression;
		this.portIdentifier = builder.portIdentifier;
		this.portName = builder.portName;
		this.penalizationNanos = builder.penalizationNanos;
	}
	
	
	private String getPortIdentifier(final TransferDirection direction) throws IOException {
		final String id = this.portIdentifier;
		if ( id != null ) {
			return id;
		}
		
		if ( direction == TransferDirection.SEND ) {
			return pool.getInputPortIdentifier(this.portName);
		} else {
			return pool.getOutputPortIdentifier(this.portName);
		}
	}
	
	
	@Override
	public Transaction createTransaction(final TransferDirection direction) throws IOException {
		final String portId = getPortIdentifier(TransferDirection.SEND);
		
		if ( portId == null ) {
			throw new IOException("Could not find Port with name " + portName + " for remote NiFi instance");
		}
		
		final RemoteDestination remoteDestination = new RemoteDestination() {
			@Override
			public String getIdentifier() {
				return portId;
			}

			@Override
			public long getYieldPeriod(final TimeUnit timeUnit) {
				return timeUnit.convert(penalizationNanos, TimeUnit.NANOSECONDS);
			}

			@Override
			public boolean isUseCompression() {
				return compress;
			}
		};
		
		final EndpointConnectionState connectionState;
		try {
			connectionState = pool.getEndpointConnectionState(remoteDestination, direction);
		} catch (final ProtocolException | HandshakeException | PortNotRunningException | UnknownPortException e) {
			throw new IOException(e);
		}
		
		final Transaction transaction = connectionState.getSocketClientProtocol().startTransaction(
				connectionState.getPeer(), connectionState.getCodec(), direction);
		
		// Wrap the transaction in a new one that will return the EndpointConnectionState back to the pool whenever
		// the transaction is either completed or canceled.
		final ObjectHolder<EndpointConnectionState> connectionStateRef = new ObjectHolder<>(connectionState);
		return new Transaction() {
			@Override
			public void confirm() throws IOException {
				transaction.confirm();
			}

			@Override
			public void complete(final boolean requestBackoff) throws IOException {
				try {
					transaction.complete(requestBackoff);
				} finally {
				    final EndpointConnectionState state = connectionStateRef.get();
				    if ( state != null ) {
				        pool.offer(connectionState);
				        connectionStateRef.set(null);
				    }
				}
			}

			@Override
			public void cancel(final String explanation) throws IOException {
				try {
					transaction.cancel(explanation);
				} finally {
                    final EndpointConnectionState state = connectionStateRef.get();
                    if ( state != null ) {
                        pool.terminate(connectionState);
                        connectionStateRef.set(null);
                    }
				}
			}

			@Override
			public void error() {
			    try {
			        transaction.error();
			    } finally {
                    final EndpointConnectionState state = connectionStateRef.get();
                    if ( state != null ) {
                        pool.terminate(connectionState);
                        connectionStateRef.set(null);
                    }
			    }
			}
			
			@Override
			public void send(final DataPacket dataPacket) throws IOException {
				transaction.send(dataPacket);
			}

			@Override
			public DataPacket receive() throws IOException {
				return transaction.receive();
			}

			@Override
			public TransactionState getState() throws IOException {
				return transaction.getState();
			}
			
		};
	}

	
	@Override
	public void close() throws IOException {
		pool.shutdown();
	}

	
	public static class Builder {
		private String url;
		private long timeoutNanos = TimeUnit.SECONDS.toNanos(30);
		private long penalizationNanos = TimeUnit.SECONDS.toNanos(3);
		private SSLContext sslContext;
		private EventReporter eventReporter;
		private File peerPersistenceFile;
		private boolean useCompression;
		private String portName;
		private String portIdentifier;
		
		public Builder url(final String url) {
			this.url = url;
			return this;
		}
		
		public Builder timeout(final long timeout, final TimeUnit unit) {
			this.timeoutNanos = unit.toNanos(timeout);
			return this;
		}
		
		public Builder nodePenalizationPeriod(final long period, final TimeUnit unit) {
			this.penalizationNanos = unit.toNanos(period);
			return this;
		}
		
		public Builder sslContext(final SSLContext sslContext) {
			this.sslContext = sslContext;
			return this;
		}
		
		public Builder eventReporter(final EventReporter eventReporter) {
			this.eventReporter = eventReporter;
			return this;
		}
		
		public Builder peerPersistenceFile(final File peerPersistenceFile) {
			this.peerPersistenceFile = peerPersistenceFile;
			return this;
		}
		
		public Builder useCompression(final boolean compress) {
			this.useCompression = compress;
			return this;
		}
		
		public Builder portName(final String portName) {
			this.portName = portName;
			return this;
		}
		
		public Builder portIdentifier(final String portIdentifier) {
			this.portIdentifier = portIdentifier;
			return this;
		}
		
		public SocketClient build() {
			if ( url == null ) {
				throw new IllegalStateException("Must specify URL to build Site-to-Site client");
			}
			
			if ( portName == null && portIdentifier == null ) {
				throw new IllegalStateException("Must specify either Port Name or Port Identifier to builder Site-to-Site client");
			}
			
			return new SocketClient(this);
		}
	}
}
