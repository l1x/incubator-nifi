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
import java.io.InputStream;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;

import org.apache.nifi.remote.Peer;
import org.apache.nifi.remote.TransferDirection;
import org.apache.nifi.remote.client.Transaction;
import org.apache.nifi.remote.io.CompressionInputStream;

public class SocketClientTransaction implements Transaction {
	private final long startTime = System.nanoTime();
	private long bytesReceived = 0L;
	private CRC32 crc = new CRC32();
	
	private final Peer peer;
	private final TransferDirection direction;
	
	private final DataInputStream dis;
	private final DataOutputStream dos;
	private final CheckedInputStream checkedInputStream;
	
	SocketClientTransaction(final Peer peer, final TransferDirection direction, final boolean useCompression) throws IOException {
		this.peer = peer;
		this.direction = direction;
		
		this.dis = new DataInputStream(peer.getCommunicationsSession().getInput().getInputStream());
		this.dos = new DataOutputStream(peer.getCommunicationsSession().getOutput().getOutputStream());
		
		final InputStream dataInputStream = useCompression ? new CompressionInputStream(dis) : dis;
        checkedInputStream = new CheckedInputStream(dataInputStream, crc);
	}
	
	CheckedInputStream getCheckedInputStream() {
		return checkedInputStream;
	}
	
	DataOutputStream getDataOutputStream() {
		return dos;
	}
	
	Peer getPeer() {
		return peer;
	}
	
}
