/**
 * Copyright 2020 Connor Holloway
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
package uk.ac.cam.cl.dtg.isaac.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The DTO which is used to receive the values required for segue authentication.
 */
public class LocalAuthDTO {
    private String randomPadding;
    private String email;
    private String password;
    private Boolean rememberMe;

    /**
     * Default constructor.
     */
    public LocalAuthDTO() {}

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Boolean getRememberMe() {
        return rememberMe;
    }

    public void setRememberMe(Boolean rememberMe) {
        this.rememberMe = rememberMe;
    }

    @JsonProperty("_randomPadding")
    public String getRandomPadding() {
        return randomPadding;
    }

    @JsonProperty("_randomPadding")
    public void setRandomPadding(String _randomPadding) {
        this.randomPadding = _randomPadding;
    }
}
