/**
 * Copyright 2017 James Sharkey
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
package uk.ac.cam.cl.dtg.segue.dto.content;

/**
 * DTO to represent a Quantity choice.
 *
 */
public class RegexChoiceDTO extends ChoiceDTO {
    private boolean caseInsensitive;

    /**
     * Default constructor required for mapping.
     */
    public RegexChoiceDTO() {

    }

    /**
     * @return Whether this regex choice should allow any case to match.
     */
    public boolean isCaseInsensitive() {
        return caseInsensitive;
    }

    /**
     * Whether to match strictly to case or not.
     *
     * @param caseInsensitive Whether to ignore the case when checking.
     */
    public void setCaseInsensitive(final boolean caseInsensitive) {
        this.caseInsensitive = caseInsensitive;
    }
}
