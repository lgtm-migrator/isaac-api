/*
 * Copyright 2019 James Sharkey
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

import uk.ac.cam.cl.dtg.segue.dos.content.JsonContentType;
import uk.ac.cam.cl.dtg.segue.dto.content.ParsonsItemDTO;

import java.util.List;

/**
 * Content DTO for IsaacParsonsQuestions.
 *
 */
@JsonContentType("isaacParsonsQuestion")
public class IsaacParsonsQuestionDTO extends IsaacQuestionBaseDTO {

    private List<ParsonsItemDTO> items;
    private Boolean disableIndentation;

    public List<ParsonsItemDTO> getItems() {
        return items;
    }

    public void setItems(final List<ParsonsItemDTO> items) {
        this.items = items;
    }

    public Boolean getDisableIndentation() {
        return disableIndentation;
    }

    public void setDisableIndentation(final Boolean disableIndentation) {
        this.disableIndentation = disableIndentation;
    }
}