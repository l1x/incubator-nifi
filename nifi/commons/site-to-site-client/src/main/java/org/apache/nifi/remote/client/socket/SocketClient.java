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

import java.io.IOException;

import org.apache.nifi.remote.client.DataPacket;
import org.apache.nifi.remote.client.SiteToSiteClient;

public class SocketClient implements SiteToSiteClient {

	@Override
	public void send(final DataPacket dataPacket) throws IOException {
		// TODO Auto-generated method stub
	}

	@Override
	public DataPacket receive() throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

}
