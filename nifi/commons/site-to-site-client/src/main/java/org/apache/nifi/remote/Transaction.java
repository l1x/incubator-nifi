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
package org.apache.nifi.remote;

import java.io.IOException;

import org.apache.nifi.remote.protocol.DataPacket;

public interface Transaction {

	void confirm() throws IOException;
	
	void complete(boolean requestBackoff) throws IOException;
	
	void cancel() throws IOException;
	
	void send(DataPacket dataPacket) throws IOException;
	
	DataPacket receive() throws IOException;
	
	TransactionState getState() throws IOException;
	
	void error();
	
	public enum TransactionState {
		TRANSACTION_STARTED,
		DATA_EXCHANGED,
		TRANSACTION_CONFIRMED,
		TRANSACTION_COMPLETED,
		TRANSACTION_CANCELED,
		ERROR;
	}
}
