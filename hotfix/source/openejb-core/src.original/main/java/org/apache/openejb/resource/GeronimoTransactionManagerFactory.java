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


package org.apache.openejb.resource;

import org.apache.geronimo.transaction.log.HOWLLog;
import org.apache.geronimo.transaction.manager.GeronimoTransactionManager;
import org.apache.geronimo.transaction.manager.TransactionLog;
import org.apache.geronimo.transaction.manager.XidFactory;
import org.apache.geronimo.transaction.manager.XidFactoryImpl;
import org.apache.geronimo.transaction.manager.WrapperNamedXAResource;
import org.apache.openejb.loader.SystemInstance;

import javax.transaction.xa.XAResource;

/**
 * @version $Rev: 1153797 $ $Date: 2011-08-04 12:09:44 +0300 (Thu, 04 Aug 2011) $
 */
public class GeronimoTransactionManagerFactory {

    private static final byte[] DEFAULT_TM_ID = new byte[]{71, 84, 77, 73, 68};
    private static final int DEFAULT_BUFFER_SIZE = 32;

    public static GeronimoTransactionManager create(int defaultTransactionTimeoutSeconds,
                                                    boolean txRecovery,
                                                    byte[] tmId,
                                                    String bufferClassName,
                                                    int bufferSizeKb,
                                                    boolean checksumEnabled,
                                                    boolean adler32Checksum,
                                                    int flushSleepTimeMilliseconds,
                                                    String logFileDir,
                                                    String logFileExt,
                                                    String logFileName,
                                                    int maxBlocksPerFile,
                                                    int maxBuffers,
                                                    int maxLogFiles,
                                                    int minBuffers,
                                                    int threadsWaitingForceThreshold) throws Exception {
        XidFactory xidFactory = null;
        TransactionLog txLog = null;
        if (txRecovery) {
            SystemInstance.get().setComponent(XAResourceWrapper.class, new GeronimoXAResourceWrapper());
            
            xidFactory = new XidFactoryImpl(tmId == null ? DEFAULT_TM_ID: tmId);
            txLog = new HOWLLog(bufferClassName == null ? "org.apache.howl.log.BlockLogBuffer" : bufferClassName,
                    bufferSizeKb == 0 ? DEFAULT_BUFFER_SIZE : bufferSizeKb,
                    checksumEnabled,
                    adler32Checksum,
                    flushSleepTimeMilliseconds,
                    logFileDir,
                    logFileExt,
                    logFileName,
                    maxBlocksPerFile,
                    maxBuffers,
                    maxLogFiles,
                    minBuffers,
                    threadsWaitingForceThreshold,
                    xidFactory,
                    SystemInstance.get().getBase().getDirectory("."));
            ((HOWLLog)txLog).doStart();
        }

        return new GeronimoTransactionManager(defaultTransactionTimeoutSeconds, xidFactory, txLog);
    }

    public static class GeronimoXAResourceWrapper implements XAResourceWrapper {
        public XAResource wrap(XAResource xaResource, String name) {
            return new WrapperNamedXAResource(xaResource, name);
        }
    }
}
