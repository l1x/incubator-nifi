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
package org.apache.nifi.remote.protocol.socket;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;

import org.apache.nifi.remote.Peer;
import org.apache.nifi.remote.Transaction;
import org.apache.nifi.remote.TransferDirection;
import org.apache.nifi.remote.codec.FlowFileCodec;
import org.apache.nifi.remote.exception.ProtocolException;
import org.apache.nifi.remote.protocol.DataPacket;
import org.apache.nifi.remote.protocol.RequestType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SocketClientTransaction implements Transaction {
	private static final Logger logger = LoggerFactory.getLogger(SocketClientTransaction.class);
	
	
	private final CRC32 crc = new CRC32();
	private final int protocolVersion;
	private final FlowFileCodec codec;
	private final DataInputStream dis;
	private final DataOutputStream dos;
	private final TransferDirection direction;
	private final boolean compress;
	private final Peer peer;
	private final int penaltyMillis;
	
	private boolean dataAvailable = false;
	private int transfers = 0;
	private TransactionState state;
	
	SocketClientTransaction(final int protocolVersion, final Peer peer, final FlowFileCodec codec, 
			final TransferDirection direction, final boolean useCompression, final int penaltyMillis) throws IOException {
		this.protocolVersion = protocolVersion;
		this.peer = peer;
		this.codec = codec;
		this.direction = direction;
		this.dis = new DataInputStream(peer.getCommunicationsSession().getInput().getInputStream());
		this.dos = new DataOutputStream(peer.getCommunicationsSession().getOutput().getOutputStream());
		this.compress = useCompression;
		this.state = TransactionState.TRANSACTION_STARTED;
		this.penaltyMillis = penaltyMillis;
		
		initialize();
	}
	
	private void initialize() throws IOException {
	    try {
            if ( direction == TransferDirection.RECEIVE ) {
                // Indicate that we would like to have some data
                RequestType.RECEIVE_FLOWFILES.writeRequestType(dos);
                dos.flush();
                
                final Response dataAvailableCode = Response.read(dis);
                switch (dataAvailableCode.getCode()) {
                    case MORE_DATA:
                        logger.debug("{} {} Indicates that data is available", this, peer);
                        this.dataAvailable = true;
                        break;
                    case NO_MORE_DATA:
                        logger.debug("{} No data available from {}", peer);
                        this.dataAvailable = false;
                        return;
                    default:
                        throw new ProtocolException("Got unexpected response when asking for data: " + dataAvailableCode);
                }
    
            } else {
                // Indicate that we would like to have some data
                RequestType.SEND_FLOWFILES.writeRequestType(dos);
                dos.flush();
            }
	    } catch (final Exception e) {
	        error();
	        throw e;
	    }
	}
	
	
	@Override
	public DataPacket receive() throws IOException {
	    try {
    		if ( state != TransactionState.DATA_EXCHANGED && state != TransactionState.TRANSACTION_STARTED) {
    			throw new IllegalStateException("Cannot receive data because Transaction State is " + state);
    		}
    		
        	if ( direction == TransferDirection.SEND ) {
        	    throw new IllegalStateException("Attempting to receive data but started a SEND Transaction");
        	}
    
        	// if no data available, return null
        	if ( !dataAvailable ) {
        	    return null;
        	}
        	
            logger.debug("{} Receiving data from {}", this, peer);
            final DataPacket packet = codec.decode(new CheckedInputStream(dis, crc));
            
            if ( packet != null ) {
            	transfers++;
                
                // Determine if Peer will send us data or has no data to send us
                final Response dataAvailableCode = Response.read(dis);
                switch (dataAvailableCode.getCode()) {
                    case MORE_DATA:
                        logger.debug("{} {} Indicates that data is available", this, peer);
                        this.dataAvailable = true;
                        break;
                    case NO_MORE_DATA:
                        logger.debug("{} No data available from {}", peer);
                        this.dataAvailable = false;
                        break;
                    default:
                        throw new ProtocolException("Got unexpected response when asking for data: " + dataAvailableCode);
                }
            }
            
            this.state = TransactionState.DATA_EXCHANGED;
            return packet;
	    } catch (final Exception e) {
	        error();
	        throw e;
	    }
	}
	
	
	@Override
	public void send(DataPacket dataPacket) throws IOException {
	    try {
    		if ( state != TransactionState.DATA_EXCHANGED && state != TransactionState.TRANSACTION_STARTED) {
    			throw new IllegalStateException("Cannot send data because Transaction State is " + state);
    		}
    
            if ( direction == TransferDirection.RECEIVE ) {
                throw new IllegalStateException("Attempting to send data but started a RECEIVE Transaction");
            }
    
    		if ( transfers > 0 ) {
                ResponseCode.CONTINUE_TRANSACTION.writeResponse(dos);
            }
    
            logger.debug("{} Sending data to {}", this, peer);
    
    		final OutputStream out = new CheckedOutputStream(dos, crc);
            codec.encode(dataPacket, out);
            
            // need to close the CompressionOutputStream in order to force it write out any remaining bytes.
            // Otherwise, do NOT close it because we don't want to close the underlying stream
            // (CompressionOutputStream will not close the underlying stream when it's closed)
            if ( compress ) {
            	out.close();
            }
            
            transfers++;
            this.state = TransactionState.DATA_EXCHANGED;
	    } catch (final Exception e) {
	        error();
	        throw e;
	    }
	}
	
	
	@Override
	public void cancel(final String explanation) throws IOException {
		if ( state == TransactionState.TRANSACTION_CANCELED || state == TransactionState.TRANSACTION_COMPLETED || state == TransactionState.ERROR ) {
			throw new IllegalStateException("Cannot cancel transaction because state is already " + state);
		}

		try {
		    ResponseCode.CANCEL_TRANSACTION.writeResponse(dos, explanation == null ? "<No explanation given>" : explanation);
		    state = TransactionState.TRANSACTION_CANCELED;
		} catch (final IOException ioe) {
		    error();
		    throw ioe;
		}
	}
	
	
	@Override
	public void complete(boolean requestBackoff) throws IOException {
	    try {
    		if ( state != TransactionState.TRANSACTION_CONFIRMED ) {
    			throw new IllegalStateException("Cannot complete transaction because state is " + state + 
    					"; Transaction can only be completed when state is " + TransactionState.TRANSACTION_CONFIRMED);
    		}
    		
    		if ( direction == TransferDirection.RECEIVE ) {
                if ( requestBackoff ) {
                    // Confirm that we received the data and the peer can now discard it but that the peer should not
                    // send any more data for a bit
                    logger.debug("{} Sending TRANSACTION_FINISHED_BUT_DESTINATION_FULL to {}", this, peer);
                    ResponseCode.TRANSACTION_FINISHED_BUT_DESTINATION_FULL.writeResponse(dos);
                } else {
                    // Confirm that we received the data and the peer can now discard it
                    logger.debug("{} Sending TRANSACTION_FINISHED to {}", this, peer);
                    ResponseCode.TRANSACTION_FINISHED.writeResponse(dos);
                }
            } else {
                final Response transactionResponse;
                try {
                    transactionResponse = Response.read(dis);
                } catch (final IOException e) {
                    throw new IOException(this + " Failed to receive a response from " + peer + " when expecting a TransactionFinished Indicator. " +
                            "It is unknown whether or not the peer successfully received/processed the data.", e);
                }
                
                logger.debug("{} Received {} from {}", this, transactionResponse, peer);
                if ( transactionResponse.getCode() == ResponseCode.TRANSACTION_FINISHED_BUT_DESTINATION_FULL ) {
                    peer.penalize(penaltyMillis);
                } else if ( transactionResponse.getCode() != ResponseCode.TRANSACTION_FINISHED ) {
                    throw new ProtocolException("After sending data, expected TRANSACTION_FINISHED response but got " + transactionResponse);
                }
            }
	    } catch (final Exception e) {
	        error();
	        throw e;
	    }
	}
	
	
	@Override
	public void confirm() throws IOException {
	    try {
    		if ( state != TransactionState.DATA_EXCHANGED ) {
    			throw new IllegalStateException("Cannot confirm Transaction because state is " + state + 
    					"; Transaction can only be confirmed when state is " + TransactionState.DATA_EXCHANGED );
    		}
    
            if ( direction == TransferDirection.RECEIVE ) {
                if ( dataAvailable ) {
                    throw new IllegalStateException("Cannot complete transaction because the sender has already sent more data than client has consumed.");
                }
                
                // we received a FINISH_TRANSACTION indicator. Send back a CONFIRM_TRANSACTION message
                // to peer so that we can verify that the connection is still open. This is a two-phase commit,
                // which helps to prevent the chances of data duplication. Without doing this, we may commit the
                // session and then when we send the response back to the peer, the peer may have timed out and may not
                // be listening. As a result, it will re-send the data. By doing this two-phase commit, we narrow the
                // Critical Section involved in this transaction so that rather than the Critical Section being the
                // time window involved in the entire transaction, it is reduced to a simple round-trip conversation.
                logger.trace("{} Sending CONFIRM_TRANSACTION Response Code to {}", this, peer);
                final String calculatedCRC = String.valueOf(crc.getValue());
                ResponseCode.CONFIRM_TRANSACTION.writeResponse(dos, calculatedCRC);
                
                final Response confirmTransactionResponse = Response.read(dis);
                logger.trace("{} Received {} from {}", this, confirmTransactionResponse, peer);
                
                switch (confirmTransactionResponse.getCode()) {
                    case CONFIRM_TRANSACTION:
                        break;
                    case BAD_CHECKSUM:
                        throw new IOException(this + " Received a BadChecksum response from peer " + peer);
                    default:
                        throw new ProtocolException(this + " Received unexpected Response from peer " + peer + " : " + confirmTransactionResponse + "; expected 'Confirm Transaction' Response Code");
                }
            } else {
                logger.debug("{} Sent FINISH_TRANSACTION indicator to {}", this, peer);
                ResponseCode.FINISH_TRANSACTION.writeResponse(dos);
                
                final String calculatedCRC = String.valueOf(crc.getValue());
                
                // we've sent a FINISH_TRANSACTION. Now we'll wait for the peer to send a 'Confirm Transaction' response
                final Response transactionConfirmationResponse = Response.read(dis);
                if ( transactionConfirmationResponse.getCode() == ResponseCode.CONFIRM_TRANSACTION ) {
                    // Confirm checksum and echo back the confirmation.
                    logger.trace("{} Received {} from {}", this, transactionConfirmationResponse, peer);
                    final String receivedCRC = transactionConfirmationResponse.getMessage();
                    
                    // CRC was not used before version 4
                    if ( protocolVersion > 3 ) {
                        if ( !receivedCRC.equals(calculatedCRC) ) {
                            ResponseCode.BAD_CHECKSUM.writeResponse(dos);
                            throw new IOException(this + " Sent data to peer " + peer + " but calculated CRC32 Checksum as " + calculatedCRC + " while peer calculated CRC32 Checksum as " + receivedCRC + "; canceling transaction and rolling back session");
                        }
                    }
                    
                    ResponseCode.CONFIRM_TRANSACTION.writeResponse(dos, "");
                } else {
                    throw new ProtocolException("Expected to receive 'Confirm Transaction' response from peer " + peer + " but received " + transactionConfirmationResponse);
                }
            }
	    } catch (final Exception e) {
	        error();
	        throw e;
	    }
	}

	@Override
	public void error() {
	    this.state = TransactionState.ERROR;
	}
	
	@Override
	public TransactionState getState() {
		return state;
	}

}