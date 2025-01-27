/*
 * Copyright 2020 Stephen Cummins
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
package uk.ac.cam.cl.dtg.isaac.dos.users;

import java.util.Date;

/**
 * Immutable type to represent the secret generated by the API and sent to the client for MFA setup.
 */
public class TOTPSharedSecret {
    private Long userId;
    private String sharedSecret;
    private Date created;
    private Date lastUpdated;

    /**
     * New shared secret object.
     * @param userId - User id for traceability and can be checked by the frontend.
     * @param sharedSecret - secret string which is used by the TOTP algorithm
     * @param created - created date.
     * @param lastUpdated - last date this value was changed (not deleted).
     */
    public TOTPSharedSecret(final Long userId, final String sharedSecret, final Date created, final Date lastUpdated) {
        this.userId = userId;
        this.sharedSecret = sharedSecret;
        this.created = created;
        this.lastUpdated = lastUpdated;
    }

    /**
     * @return The user id that the secret was generated for.
     */
    public Long getUserId() {
        return userId;
    }

    /**
     * @return - the shared secret
     */
    public String getSharedSecret() {
        return sharedSecret;
    }

    /**
     * @return the creation date of the secret.
     */
    public Date getCreated() {
        return created;
    }

    /**
     * @return the last updated date of the secret.
     */
    public Date getLastUpdated() {
        return lastUpdated;
    }
}
