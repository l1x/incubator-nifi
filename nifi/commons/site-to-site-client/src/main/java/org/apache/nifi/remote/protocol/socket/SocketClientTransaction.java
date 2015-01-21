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
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;

import org.apache.nifi.remote.Peer;
import org.apache.nifi.remote.TransferDirection;

public class SocketClientTransaction {
	private final long startTime = System.nanoTime();
	private final CRC32 crc = new CRC32();
	
	private final Peer peer;
	
	private final DataInputStream dis;
	private final DataOutputStream dos;
	private final TransferDirection direction;
	
	private boolean dataAvailable = false;
	private int transfers = 0;
	
	SocketClientTransaction(final Peer peer, final TransferDirection direction, final boolean useCompression) throws IOException {
		this.peer = peer;
		this.direction = direction;
		this.dis = new DataInputStream(peer.getCommunicationsSession().getInput().getInputStream());
		this.dos = new DataOutputStream(peer.getCommunicationsSession().getOutput().getOutputStream());
	}
	
	int getTransferCount() {
	    return transfers;
	}
	
	void incrementTransferCount() {
	    transfers++;
	}
	
	void setDataAvailable(final boolean available) {
	    this.dataAvailable = available;
	}
	
	boolean isDataAvailable() {
	    return dataAvailable;
	}
	
	TransferDirection getTransferDirection() {
	    return direction;
	}
	
	DataOutputStream getDataOutputStream() {
		return dos;
	}
	
	DataInputStream getDataInputStream() {
	    return dis;
	}
	
	CheckedInputStream createCheckedInputStream() {
	    return new CheckedInputStream(dis, crc);
	}
	
	CheckedOutputStream createCheckedOutputStream() {
	    return new CheckedOutputStream(dos, crc);
	}
	
	Peer getPeer() {
		return peer;
	}
	
	String calculateCRC() {
	    return String.valueOf(crc.getValue());
	}
}
