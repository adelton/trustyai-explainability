/*
 * Copyright 2020 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kie.trustyai.explainability.model;

import java.util.Objects;
import java.util.UUID;

public abstract class BasePrediction implements Prediction {
    private final PredictionInput input;
    private final UUID executionId;
    private final PredictionOutput output;

    public BasePrediction(PredictionInput input, PredictionOutput output) {
        this(input, output, UUID.randomUUID());
    }

    public BasePrediction(PredictionInput input, PredictionOutput output, UUID executionId) {
        this.input = input;
        this.output = output;
        this.executionId = executionId;
    }

    @Override
    public PredictionInput getInput() {
        return input;
    }

    @Override
    public PredictionOutput getOutput() {
        return output;
    }

    @Override
    public UUID getExecutionId() {
        return executionId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        BasePrediction that = (BasePrediction) o;
        return Objects.equals(input, that.input) && Objects.equals(executionId, that.executionId)
                && Objects.equals(output, that.output);
    }

    @Override
    public int hashCode() {
        return Objects.hash(input, executionId, output);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" +
                "input=" + this.getInput() +
                ",output=" + this.getOutput() +
                '}';
    }
}
