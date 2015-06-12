/**
 * Copyright 2014 Stephen Cummins and Nick Rogers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * You may obtain a copy of the License at
 * 		http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.ac.cam.cl.dtg.segue.api.monitors;

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.fail;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;
import org.powermock.core.classloader.annotations.PowerMockIgnore;

import uk.ac.cam.cl.dtg.segue.api.Constants;
import uk.ac.cam.cl.dtg.segue.api.managers.SegueResourceMisuseException;
import uk.ac.cam.cl.dtg.segue.comm.EmailCommunicationMessage;
import uk.ac.cam.cl.dtg.segue.comm.EmailManager;
import uk.ac.cam.cl.dtg.util.PropertiesLoader;

/**
 * Test class for the user manager class.
 * 
 */
@PowerMockIgnore({ "javax.ws.*" })
public class MisuseMonitorTest {
    private PropertiesLoader dummyPropertiesLoader;
    private EmailManager dummyCommunicator;

    /**
     * Initial configuration of tests.
     * 
     * @throws Exception
     *             - test exception
     */
    @Before
    public final void setUp() throws Exception {
        this.dummyCommunicator = createMock(EmailManager.class);
        this.dummyPropertiesLoader = createMock(PropertiesLoader.class);

        expect(dummyPropertiesLoader.getProperty(Constants.SERVER_ADMIN_ADDRESS)).andReturn("FROM ADDRESS").anyTimes();
        replay(this.dummyPropertiesLoader);
    }

    /**
     * Verify that the misusehandler detects misuse.
     */
    @Test
    public final void misuseMonitorTokenOwnerLookup_checkForMisuse_emailShouldBeSentAndExceptionShouldOccur() {
        String userId = "289347298428";
        String event = TokenOwnerLookupMisuseHandler.class.toString();

        IMisuseMonitor misuseMonitor = new InMemoryMisuseMonitor();
        TokenOwnerLookupMisuseHandler tokenOwnerLookupMisuseHandler = new TokenOwnerLookupMisuseHandler(
                dummyCommunicator, dummyPropertiesLoader);

        misuseMonitor.registerHandler(event, tokenOwnerLookupMisuseHandler);

        dummyCommunicator.addToQueue(EasyMock.isA(EmailCommunicationMessage.class));
        expectLastCall();
        replay(this.dummyCommunicator);

        for (int i = 1; i < tokenOwnerLookupMisuseHandler.getSoftThreshold(); i++) {
            try {
                misuseMonitor.notifyEvent(userId, event);

            } catch (SegueResourceMisuseException e) {
                fail("Exception should not be thrown after " + tokenOwnerLookupMisuseHandler.getSoftThreshold()
                        + " attempts");
            }
        }

        for (int i = TokenOwnerLookupMisuseHandler.SOFT_THRESHOLD; i < TokenOwnerLookupMisuseHandler.HARD_THRESHOLD; i++) {
            try {
                misuseMonitor.notifyEvent(userId, event);
                if (i > TokenOwnerLookupMisuseHandler.HARD_THRESHOLD) {
                    fail("Exception have been thrown after " + TokenOwnerLookupMisuseHandler.HARD_THRESHOLD
                            + " attempts");
                }
            } catch (SegueResourceMisuseException e) {

            }
        }

        verify(this.dummyCommunicator, this.dummyPropertiesLoader);
    }
}