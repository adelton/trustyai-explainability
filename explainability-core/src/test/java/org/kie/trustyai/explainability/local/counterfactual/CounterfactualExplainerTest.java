/*
 * Copyright 2021 Red Hat, Inc. and/or its affiliates.
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
package org.kie.trustyai.explainability.local.counterfactual;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.apache.commons.math3.distribution.NormalDistribution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.kie.trustyai.explainability.Config;
import org.kie.trustyai.explainability.local.counterfactual.entities.CategoricalNumericalEntity;
import org.kie.trustyai.explainability.local.counterfactual.entities.CounterfactualEntity;
import org.kie.trustyai.explainability.local.counterfactual.goal.CounterfactualGoalCriteria;
import org.kie.trustyai.explainability.local.counterfactual.goal.DefaultCounterfactualGoalCriteria;
import org.kie.trustyai.explainability.local.counterfactual.goal.GoalScore;
import org.kie.trustyai.explainability.local.counterfactual.score.MockCounterFactualScoreCalculator;
import org.kie.trustyai.explainability.model.CounterfactualPrediction;
import org.kie.trustyai.explainability.model.DataDomain;
import org.kie.trustyai.explainability.model.Feature;
import org.kie.trustyai.explainability.model.FeatureDistribution;
import org.kie.trustyai.explainability.model.FeatureFactory;
import org.kie.trustyai.explainability.model.NumericFeatureDistribution;
import org.kie.trustyai.explainability.model.Output;
import org.kie.trustyai.explainability.model.PerturbationContext;
import org.kie.trustyai.explainability.model.Prediction;
import org.kie.trustyai.explainability.model.PredictionInput;
import org.kie.trustyai.explainability.model.PredictionOutput;
import org.kie.trustyai.explainability.model.PredictionProvider;
import org.kie.trustyai.explainability.model.Type;
import org.kie.trustyai.explainability.model.Value;
import org.kie.trustyai.explainability.model.domain.CategoricalFeatureDomain;
import org.kie.trustyai.explainability.model.domain.CategoricalNumericalFeatureDomain;
import org.kie.trustyai.explainability.model.domain.EmptyFeatureDomain;
import org.kie.trustyai.explainability.model.domain.FeatureDomain;
import org.kie.trustyai.explainability.model.domain.NumericalFeatureDomain;
import org.kie.trustyai.explainability.utils.DataUtils;
import org.kie.trustyai.explainability.utils.models.TestModels;
import org.mockito.ArgumentCaptor;
import org.optaplanner.core.api.score.buildin.bendablebigdecimal.BendableBigDecimalScore;
import org.optaplanner.core.api.solver.SolverJob;
import org.optaplanner.core.api.solver.SolverManager;
import org.optaplanner.core.config.solver.EnvironmentMode;
import org.optaplanner.core.config.solver.SolverConfig;
import org.optaplanner.core.config.solver.termination.TerminationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.kie.trustyai.explainability.local.counterfactual.CounterfactualUtils.DEFAULT_GOAL_THRESHOLD;
import static org.kie.trustyai.explainability.local.counterfactual.CounterfactualUtils.MAX_RUNNING_TIME_SECONDS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CounterfactualExplainerTest {

    private static final Logger logger =
            LoggerFactory.getLogger(CounterfactualExplainerTest.class);
    private Function<SolverConfig, SolverManager<CounterfactualSolution, UUID>> solverManagerFactory;
    private SolverManager<CounterfactualSolution, UUID> solverManager;

    @BeforeEach
    @SuppressWarnings({ "unused", "unchecked" })
    protected void setup() {
        this.solverManagerFactory = mock(Function.class);
        this.solverManager = mock(SolverManager.class);
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2 })
    void testNonEmptyInput(int seed) throws ExecutionException, InterruptedException, TimeoutException {
        Random random = new Random();
        random.setSeed(seed);

        final List<Output> goal = List.of(new Output("class", Type.NUMBER, new Value(10.0), 0.0d));
        List<Feature> features = new LinkedList<>();
        for (int i = 0; i < 4; i++) {
            features.add(FeatureFactory.newNumericalFeature("f-" + i, random.nextDouble(), NumericalFeatureDomain.create(0.0, 1000.0)));
        }
        final TerminationConfig terminationConfig = new TerminationConfig().withScoreCalculationCountLimit(10L);
        // for the purpose of this test, only a few steps are necessary
        final SolverConfig solverConfig = SolverConfigBuilder
                .builder().withTerminationConfig(terminationConfig).build();
        solverConfig.setRandomSeed((long) seed);
        solverConfig.setEnvironmentMode(EnvironmentMode.REPRODUCIBLE);
        final CounterfactualConfig counterfactualConfig = new CounterfactualConfig().withSolverConfig(solverConfig);
        final CounterfactualExplainer counterfactualExplainer =
                new CounterfactualExplainer(counterfactualConfig);

        PredictionProvider model = TestModels.getSumSkipModel(0);

        PredictionInput input = new PredictionInput(features);
        PredictionOutput output = new PredictionOutput(goal);
        Prediction prediction =
                new CounterfactualPrediction(input,
                        output,
                        null,
                        UUID.randomUUID(),
                        null);

        final CounterfactualResult counterfactualResult = counterfactualExplainer.explainAsync(prediction, model)
                .get(Config.INSTANCE.getAsyncTimeout(), Config.INSTANCE.getAsyncTimeUnit());
        for (CounterfactualEntity entity : counterfactualResult.getEntities()) {
            logger.debug("Entity: {}", entity);
        }

        logger.debug("Outputs: {}", counterfactualResult.getOutput().get(0).getOutputs());
        assertNotNull(counterfactualResult);
        assertNotNull(counterfactualResult.getEntities());
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2 })
    void testCounterfactualMatch(int seed) throws ExecutionException, InterruptedException, TimeoutException {
        Random random = new Random();
        random.setSeed(seed);

        final List<Output> goal = List.of(new Output("inside", Type.BOOLEAN, new Value(true), 0.0d));
        List<Feature> features = new LinkedList<>();
        features.add(FeatureFactory.newNumericalFeature("f-num1", 100.0, NumericalFeatureDomain.create(0.0, 1000.0)));
        features.add(FeatureFactory.newNumericalFeature("f-num2", 150.0, NumericalFeatureDomain.create(0.0, 1000.0)));
        features.add(FeatureFactory.newNumericalFeature("f-num3", 1.0, NumericalFeatureDomain.create(0.0, 1000.0)));
        features.add(FeatureFactory.newNumericalFeature("f-num4", 2.0, NumericalFeatureDomain.create(0.0, 1000.0)));

        final double center = 500.0;
        final double epsilon = 10.0;

        final CounterfactualResult result =
                CounterfactualUtils.runCounterfactualSearch((long) seed, goal, features,
                        TestModels.getSumThresholdModel(center, epsilon),
                        DEFAULT_GOAL_THRESHOLD);

        double totalSum = 0;
        for (CounterfactualEntity entity : result.getEntities()) {
            totalSum += entity.asFeature().getValue().asNumber();
            logger.debug("Entity: {}", entity);
        }

        logger.debug("Outputs: {}", result.getOutput().get(0).getOutputs());

        assertTrue(totalSum <= center + epsilon);
        assertTrue(totalSum >= center - epsilon);
        assertTrue(result.isValid());
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2 })
    void testCounterfactualConstrainedMatchUnscaled(int seed)
            throws ExecutionException, InterruptedException, TimeoutException {
        Random random = new Random();
        random.setSeed(seed);

        final List<Output> goal = List.of(new Output("inside", Type.BOOLEAN, new Value(true), 0.0));

        List<Feature> features = new LinkedList<>();
        features.add(FeatureFactory.newNumericalFeature("f-num1", 100.0));
        features.add(FeatureFactory.newNumericalFeature("f-num2", 100.0, NumericalFeatureDomain.create(0.0, 1000.0)));
        features.add(FeatureFactory.newNumericalFeature("f-num3", 100.0, NumericalFeatureDomain.create(0.0, 1000.0)));
        features.add(FeatureFactory.newNumericalFeature("f-num4", 100.0));

        final double center = 500.0;
        final double epsilon = 10.0;

        final CounterfactualResult result =
                CounterfactualUtils.runCounterfactualSearch((long) seed, goal, features,
                        TestModels.getSumThresholdModel(center, epsilon),
                        DEFAULT_GOAL_THRESHOLD);

        final List<CounterfactualEntity> counterfactualEntities = result.getEntities();
        double totalSum = 0;
        for (CounterfactualEntity entity : counterfactualEntities) {
            totalSum += entity.asFeature().getValue().asNumber();
            logger.debug("Entity: {}", entity);
        }
        assertFalse(counterfactualEntities.get(0).isChanged());
        assertFalse(counterfactualEntities.get(3).isChanged());
        assertTrue(totalSum <= center + epsilon);
        assertTrue(totalSum >= center - epsilon);
        assertTrue(result.isValid());
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2 })
    void testCounterfactualConstrainedMatchScaled(int seed) throws ExecutionException, InterruptedException, TimeoutException {
        Random random = new Random();
        random.setSeed(seed);
        final List<Output> goal = List.of(new Output("inside", Type.BOOLEAN, new Value(true), 0.0d));

        List<Feature> features = new LinkedList<>();
        List<FeatureDistribution> featureDistributions = new LinkedList<>();

        final Feature fnum1 = FeatureFactory.newNumericalFeature("f-num1", 100.0);
        features.add(fnum1);
        featureDistributions.add(new NumericFeatureDistribution(fnum1, (new NormalDistribution(500, 1.1)).sample(1000)));

        final Feature fnum2 = FeatureFactory.newNumericalFeature("f-num2", 100.0, NumericalFeatureDomain.create(0.0, 1000.0));
        features.add(fnum2);
        featureDistributions.add(new NumericFeatureDistribution(fnum2, (new NormalDistribution(430.0, 1.7)).sample(1000)));

        final Feature fnum3 = FeatureFactory.newNumericalFeature("f-num3", 100.0, NumericalFeatureDomain.create(0.0, 1000.0));
        features.add(fnum3);
        featureDistributions.add(new NumericFeatureDistribution(fnum3, (new NormalDistribution(470.0, 2.9)).sample(1000)));

        final Feature fnum4 = FeatureFactory.newNumericalFeature("f-num4", 100.0);
        features.add(fnum4);
        featureDistributions.add(new NumericFeatureDistribution(fnum4, (new NormalDistribution(2390.0, 0.3)).sample(1000)));

        final double center = 500.0;
        final double epsilon = 10.0;

        final CounterfactualResult result =
                CounterfactualUtils.runCounterfactualSearch((long) seed, goal, features,
                        TestModels.getSumThresholdModel(center, epsilon),
                        DEFAULT_GOAL_THRESHOLD);

        final List<CounterfactualEntity> counterfactualEntities = result.getEntities();

        double totalSum = 0;
        for (CounterfactualEntity entity : counterfactualEntities) {
            totalSum += entity.asFeature().getValue().asNumber();
            logger.debug("Entity: {}", entity);
        }
        assertFalse(counterfactualEntities.get(0).isChanged());
        assertFalse(counterfactualEntities.get(3).isChanged());
        assertTrue(totalSum <= center + epsilon);
        assertTrue(totalSum >= center - epsilon);
        assertTrue(result.isValid());
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2 })
    void testCounterfactualBoolean(int seed) throws ExecutionException, InterruptedException, TimeoutException {
        Random random = new Random();
        random.setSeed(seed);
        final List<Output> goal = List.of(new Output("inside", Type.BOOLEAN, new Value(true), 0.0d));

        List<Feature> features = new LinkedList<>();
        for (int i = 0; i < 4; i++) {
            if (i == 2) {
                features.add(FeatureFactory.newNumericalFeature("f-" + i, random.nextDouble()));
            } else {
                features.add(FeatureFactory.newNumericalFeature("f-" + i, random.nextDouble(), NumericalFeatureDomain.create(0.0, 1000.0)));
            }
        }
        features.add(FeatureFactory.newBooleanFeature("f-bool", true, EmptyFeatureDomain.create()));

        final double center = 500.0;
        final double epsilon = 10.0;

        final CounterfactualResult result =
                CounterfactualUtils.runCounterfactualSearch((long) seed, goal, features,
                        TestModels.getSumThresholdModel(center, epsilon),
                        0.005, 200_000);

        final List<CounterfactualEntity> counterfactualEntities = result.getEntities();

        double totalSum = 0;
        for (CounterfactualEntity entity : counterfactualEntities) {
            totalSum += entity.asFeature().getValue().asNumber();
            logger.debug("Entity: {}", entity);
        }
        assertFalse(counterfactualEntities.get(2).isChanged());
        assertTrue(totalSum <= center + epsilon);
        assertTrue(totalSum >= center - epsilon);
        assertTrue(result.isValid());
    }

    /**
     * Search for a counterfactual using categorical features with the Symbolic arithmetic model.
     * The outcome match is strict (goal threshold of zero).
     * The CF should be invalid with this number of iterations.
     *
     * @param seed
     * @throws ExecutionException
     * @throws InterruptedException
     * @throws TimeoutException
     */
    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2 })
    void testCounterfactualCategoricalStrictFail(int seed) throws ExecutionException, InterruptedException, TimeoutException {
        Random random = new Random();
        random.setSeed(seed);
        final List<Output> goal = List.of(new Output("result", Type.NUMBER, new Value(25.0), 0.0d));

        List<Feature> features = new LinkedList<>();
        features.add(FeatureFactory.newNumericalFeature("x-1", 5.0, NumericalFeatureDomain.create(0.0, 100.0)));
        features.add(FeatureFactory.newNumericalFeature("x-2", 40.0, NumericalFeatureDomain.create(0.0, 100.0)));
        features.add(FeatureFactory.newCategoricalFeature("operand", "*", CategoricalFeatureDomain.create("+", "-", "/", "*")));

        final CounterfactualResult result =
                CounterfactualUtils.runCounterfactualSearch((long) seed, goal, features,
                        TestModels.getSymbolicArithmeticModel(),
                        0.0);

        final List<CounterfactualEntity> counterfactualEntities = result.getEntities();

        Stream<Feature> counterfactualFeatures = counterfactualEntities
                .stream()
                .map(CounterfactualEntity::asFeature);
        String operand = counterfactualFeatures
                .filter(feature -> feature.getName().equals("operand"))
                .findFirst()
                .get()
                .getValue()
                .asString();

        List<Feature> numericalFeatures = counterfactualEntities
                .stream()
                .map(CounterfactualEntity::asFeature)
                .filter(feature -> !feature.getName().equals("operand"))
                .collect(Collectors.toList());

        double opResult = 0.0;
        for (Feature feature : numericalFeatures) {
            switch (operand) {
                case "+":
                    opResult += feature.getValue().asNumber();
                    break;
                case "-":
                    opResult -= feature.getValue().asNumber();
                    break;
                case "*":
                    opResult *= feature.getValue().asNumber();
                    break;
                case "/":
                    opResult /= feature.getValue().asNumber();
                    break;
            }
        }
        final double epsilon = 0.1;
        assertFalse(result.isValid());
        assertTrue(opResult <= 25.0 + epsilon);
        assertTrue(opResult >= 25.0 - epsilon);
    }

    /**
     * Search for a counterfactual using categorical features with the Symbolic arithmetic model.
     * The outcome match is not strict (goal threshold of 0.01).
     * The CF should be valid with this number of iterations.
     *
     * @param seed
     * @throws ExecutionException
     * @throws InterruptedException
     * @throws TimeoutException
     */
    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2 })
    void testCounterfactualCategoricalNotStrict(int seed) throws ExecutionException, InterruptedException, TimeoutException {
        Random random = new Random();
        random.setSeed(seed);
        final List<Output> goal = List.of(new Output("result", Type.NUMBER, new Value(25.0), 0.0d));

        List<Feature> features = new LinkedList<>();
        features.add(FeatureFactory.newNumericalFeature("x-1", 5.0, NumericalFeatureDomain.create(0.0, 100.0)));
        features.add(FeatureFactory.newNumericalFeature("x-2", 40.0, NumericalFeatureDomain.create(0.0, 100.0)));
        features.add(FeatureFactory.newCategoricalFeature("operand", "*", CategoricalFeatureDomain.create("+", "-", "/", "*")));

        final PredictionProvider model = TestModels.getSymbolicArithmeticModel();
        final CounterfactualResult result =
                CounterfactualUtils.runCounterfactualSearch((long) seed, features, model, 0.01, DefaultCounterfactualGoalCriteria.create(goal, 0.01), 100_000);

        final List<CounterfactualEntity> counterfactualEntities = result.getEntities();

        Stream<Feature> counterfactualFeatures = counterfactualEntities
                .stream()
                .map(CounterfactualEntity::asFeature);
        String operand = counterfactualFeatures
                .filter(feature -> feature.getName().equals("operand"))
                .findFirst()
                .get()
                .getValue()
                .asString();

        List<Feature> numericalFeatures = counterfactualEntities
                .stream()
                .map(CounterfactualEntity::asFeature)
                .filter(feature -> !feature.getName().equals("operand"))
                .collect(Collectors.toList());

        double opResult = 0.0;
        for (Feature feature : numericalFeatures) {
            switch (operand) {
                case "+":
                    opResult += feature.getValue().asNumber();
                    break;
                case "-":
                    opResult -= feature.getValue().asNumber();
                    break;
                case "*":
                    opResult *= feature.getValue().asNumber();
                    break;
                case "/":
                    opResult /= feature.getValue().asNumber();
                    break;
            }
        }
        final double epsilon = 0.5;
        assertTrue(result.isValid());
        assertTrue(opResult <= 25.0 + epsilon);
        assertTrue(opResult >= 25.0 - epsilon);
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2 })
    void testCounterfactualMatchThreshold(int seed) throws ExecutionException, InterruptedException, TimeoutException {
        Random random = new Random();
        random.setSeed(seed);
        final double scoreThreshold = 0.9;

        final List<Output> goal = List.of(new Output("inside", Type.BOOLEAN, new Value(true), scoreThreshold));

        List<Feature> features = new LinkedList<>();
        features.add(FeatureFactory.newNumericalFeature("f-num1", 100.0, NumericalFeatureDomain.create(0.0, 1000.0)));
        features.add(FeatureFactory.newNumericalFeature("f-num2", 100.0, NumericalFeatureDomain.create(0.0, 1000.0)));
        features.add(FeatureFactory.newNumericalFeature("f-num3", 100.0, NumericalFeatureDomain.create(0.0, 1000.0)));
        features.add(FeatureFactory.newNumericalFeature("f-num4", 100.0, NumericalFeatureDomain.create(0.0, 1000.0)));

        final double center = 500.0;
        final double epsilon = 10.0;

        final PredictionProvider model = TestModels.getSumThresholdModel(center, epsilon);

        final CounterfactualResult result =
                CounterfactualUtils.runCounterfactualSearch((long) seed, goal, features,
                        model,
                        DEFAULT_GOAL_THRESHOLD, 100_000);

        final List<CounterfactualEntity> counterfactualEntities = result.getEntities();

        double totalSum = 0;
        for (CounterfactualEntity entity : counterfactualEntities) {
            totalSum += entity.asFeature().getValue().asNumber();
            logger.debug("Entity: {}", entity);
        }
        assertTrue(totalSum <= center + epsilon);
        assertTrue(totalSum >= center - epsilon);

        final List<Feature> cfFeatures =
                counterfactualEntities.stream().map(CounterfactualEntity::asFeature).collect(Collectors.toList());
        final PredictionInput cfInput = new PredictionInput(cfFeatures);
        final PredictionOutput cfOutput = model.predictAsync(List.of(cfInput))
                .get(Config.INSTANCE.getAsyncTimeout(), Config.INSTANCE.getAsyncTimeUnit())
                .get(0);

        final double predictionScore = cfOutput.getOutputs().get(0).getScore();
        logger.debug("Prediction score: {}", predictionScore);
        assertTrue(predictionScore >= scoreThreshold);
        assertTrue(result.isValid());
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2 })
    void testCounterfactualMatchNoThreshold(int seed) throws ExecutionException, InterruptedException, TimeoutException {
        Random random = new Random();
        random.setSeed(seed);
        final double scoreThreshold = 0.0;

        final List<Output> goal = List.of(new Output("inside", Type.BOOLEAN, new Value(true), scoreThreshold));

        List<Feature> features = new LinkedList<>();
        features.add(FeatureFactory.newNumericalFeature("f-num1", 100.0, NumericalFeatureDomain.create(0.0, 1000.0)));
        features.add(FeatureFactory.newNumericalFeature("f-num2", 100.0, NumericalFeatureDomain.create(0.0, 1000.0)));
        features.add(FeatureFactory.newNumericalFeature("f-num3", 100.0, NumericalFeatureDomain.create(0.0, 1000.0)));
        features.add(FeatureFactory.newNumericalFeature("f-num4", 100.0, NumericalFeatureDomain.create(0.0, 1000.0)));

        final double center = 500.0;
        final double epsilon = 10.0;

        final PredictionProvider model = TestModels.getSumThresholdModel(center, epsilon);
        final CounterfactualResult result =
                CounterfactualUtils.runCounterfactualSearch((long) seed, goal, features,
                        model,
                        DEFAULT_GOAL_THRESHOLD, 100_000);
        final List<CounterfactualEntity> counterfactualEntities = result.getEntities();

        double totalSum = 0;
        for (CounterfactualEntity entity : counterfactualEntities) {
            totalSum += entity.asFeature().getValue().asNumber();
            logger.debug("Entity: {}", entity);
        }
        assertTrue(totalSum <= center + epsilon);
        assertTrue(totalSum >= center - epsilon);

        final List<Feature> cfFeatures =
                counterfactualEntities.stream().map(CounterfactualEntity::asFeature).collect(Collectors.toList());
        final PredictionInput cfInput = new PredictionInput(cfFeatures);
        final PredictionOutput cfOutput = model.predictAsync(List.of(cfInput))
                .get(Config.INSTANCE.getAsyncTimeout(), Config.INSTANCE.getAsyncTimeUnit())
                .get(0);

        final double predictionScore = cfOutput.getOutputs().get(0).getScore();
        logger.debug("Prediction score: {}", predictionScore);
        assertTrue(predictionScore < 0.5);
        assertTrue(result.isValid());
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2 })
    void testNoCounterfactualPossible(long seed)
            throws ExecutionException, InterruptedException, TimeoutException {
        Random random = new Random();
        final PerturbationContext perturbationContext = new PerturbationContext(seed, random, 4);
        final List<Output> goal = List.of(new Output("inside", Type.BOOLEAN, new Value(true), 0.0));

        List<Feature> features = new LinkedList<>();
        List<FeatureDomain> featureBoundaries = new LinkedList<>();
        List<Boolean> constraints = new LinkedList<>();
        features.add(FeatureFactory.newNumericalFeature("f-num1", 1.0));
        constraints.add(true);
        featureBoundaries.add(EmptyFeatureDomain.create());
        features.add(FeatureFactory.newNumericalFeature("f-num2", 1.0));
        constraints.add(false);
        featureBoundaries.add(NumericalFeatureDomain.create(0.0, 2.0));
        features.add(FeatureFactory.newNumericalFeature("f-num3", 1.0));
        constraints.add(false);
        featureBoundaries.add(NumericalFeatureDomain.create(0.0, 2.0));
        features.add(FeatureFactory.newNumericalFeature("f-num4", 1.0));
        constraints.add(true);
        featureBoundaries.add(EmptyFeatureDomain.create());

        final DataDomain dataDomain = new DataDomain(featureBoundaries);

        final double center = 500.0;
        final double epsilon = 1.0;

        List<Feature> perturbedFeatures = DataUtils.perturbFeatures(features, perturbationContext);

        final CounterfactualResult result =
                CounterfactualUtils.runCounterfactualSearch((long) seed, goal, perturbedFeatures,
                        TestModels.getSumThresholdModel(center, epsilon),
                        DEFAULT_GOAL_THRESHOLD);

        assertFalse(result.isValid());
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2 })
    void testConsumers(int seed) throws ExecutionException, InterruptedException, TimeoutException {
        Random random = new Random();
        random.setSeed(seed);

        final List<Output> goal = List.of(new Output("inside", Type.BOOLEAN, new Value(true), 0.0));

        List<Feature> features = new LinkedList<>();
        features.add(FeatureFactory.newNumericalFeature("f-num1", 100.0, NumericalFeatureDomain.create(0.0, 1000.0)));
        features.add(FeatureFactory.newNumericalFeature("f-num2", 100.0, NumericalFeatureDomain.create(0.0, 1000.0)));
        features.add(FeatureFactory.newNumericalFeature("f-num3", 100.0, NumericalFeatureDomain.create(0.0, 1000.0)));
        features.add(FeatureFactory.newNumericalFeature("f-num4", 100.0, NumericalFeatureDomain.create(0.0, 1000.0)));

        final TerminationConfig terminationConfig = new TerminationConfig().withScoreCalculationCountLimit(10_000L);
        // for the purpose of this test, only a few steps are necessary
        final SolverConfig solverConfig = SolverConfigBuilder
                .builder().withTerminationConfig(terminationConfig).build();
        solverConfig.setRandomSeed((long) seed);
        solverConfig.setEnvironmentMode(EnvironmentMode.REPRODUCIBLE);

        @SuppressWarnings("unchecked")
        final Consumer<CounterfactualResult> assertIntermediateCounterfactualNotNull =
                mock(Consumer.class);
        final CounterfactualConfig counterfactualConfig =
                new CounterfactualConfig().withSolverConfig(solverConfig);
        final CounterfactualExplainer counterfactualExplainer =
                new CounterfactualExplainer(counterfactualConfig);

        PredictionInput input = new PredictionInput(features);

        final double center = 500.0;
        final double epsilon = 10.0;

        final PredictionProvider model = TestModels.getSumThresholdModel(center, epsilon);

        PredictionOutput output = new PredictionOutput(goal);
        Prediction prediction = new CounterfactualPrediction(input,
                output,
                0.01,
                null,
                UUID.randomUUID(),
                null);
        final CounterfactualResult counterfactualResult =
                counterfactualExplainer.explainAsync(prediction, model, assertIntermediateCounterfactualNotNull)
                        .get(Config.INSTANCE.getAsyncTimeout(), Config.INSTANCE.getAsyncTimeUnit());
        for (CounterfactualEntity entity : counterfactualResult.getEntities()) {
            logger.debug("Entity: {}", entity);
        }

        logger.debug("Outputs: {}", counterfactualResult.getOutput().get(0).getOutputs());
        // At least one intermediate result is generated
        verify(assertIntermediateCounterfactualNotNull, atLeast(1)).accept(any());
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 2, 3, 5, 8 })
    @SuppressWarnings("unchecked")
    void testSequenceIds(int numberOfIntermediateSolutions) throws ExecutionException, InterruptedException, TimeoutException {
        final List<Long> sequenceIds = new ArrayList<>();
        final Consumer<CounterfactualResult> captureSequenceIds = counterfactual -> {
            sequenceIds.add(counterfactual.getSequenceId());
        };

        ArgumentCaptor<Consumer<CounterfactualSolution>> intermediateSolutionConsumerCaptor =
                ArgumentCaptor.forClass(Consumer.class);
        CounterfactualResult result = mockExplainerInvocation(captureSequenceIds, null);
        verify(solverManager).solveAndListen(any(), any(), intermediateSolutionConsumerCaptor.capture(), any());
        Consumer<CounterfactualSolution> intermediateSolutionConsumer = intermediateSolutionConsumerCaptor.getValue();

        //Mock the intermediate Solution callback being invoked
        IntStream.range(0, numberOfIntermediateSolutions).forEach(i -> {
            CounterfactualSolution intermediate = mock(CounterfactualSolution.class);
            BendableBigDecimalScore intermediateScore = BendableBigDecimalScore.zero(0, 0);
            when(intermediate.getScore()).thenReturn(intermediateScore);
            intermediateSolutionConsumer.accept(intermediate);
        });

        //The final and intermediate Solutions should all have unique Sequence Ids.
        sequenceIds.add(result.getSequenceId());
        assertEquals(numberOfIntermediateSolutions + 1, sequenceIds.size());
        assertEquals(numberOfIntermediateSolutions + 1, (int) sequenceIds.stream().distinct().count());
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2 })
    void testIntermediateUniqueIds(int seed) throws ExecutionException, InterruptedException, TimeoutException {
        Random random = new Random();
        random.setSeed(seed);

        final List<Output> goal = new ArrayList<>();

        List<Feature> features = List.of(FeatureFactory.newNumericalFeature("f-num1", 10.0,
                NumericalFeatureDomain.create(0, 20)));

        PredictionProvider model = TestModels.getFeaturePassModel(0);

        final TerminationConfig terminationConfig =
                new TerminationConfig().withScoreCalculationCountLimit(100_000L);
        final SolverConfig solverConfig = SolverConfigBuilder
                .builder().withTerminationConfig(terminationConfig)
                .build();

        solverConfig.setRandomSeed((long) seed);
        solverConfig.setEnvironmentMode(EnvironmentMode.REPRODUCIBLE);

        final List<UUID> intermediateIds = new ArrayList<>();
        final List<UUID> executionIds = new ArrayList<>();

        final Consumer<CounterfactualResult> captureIntermediateIds = counterfactual -> {
            intermediateIds.add(counterfactual.getSolutionId());
        };

        final Consumer<CounterfactualResult> captureExecutionIds = counterfactual -> {
            executionIds.add(counterfactual.getExecutionId());
        };

        final CounterfactualConfig counterfactualConfig = new CounterfactualConfig().withSolverConfig(solverConfig);

        solverConfig.withEasyScoreCalculatorClass(MockCounterFactualScoreCalculator.class);
        final CounterfactualExplainer counterfactualExplainer =
                new CounterfactualExplainer(counterfactualConfig);

        PredictionInput input = new PredictionInput(features);
        PredictionOutput output = new PredictionOutput(goal);
        final UUID executionId = UUID.randomUUID();
        Prediction prediction = new CounterfactualPrediction(input,
                output,
                null,
                executionId,
                null);
        final CounterfactualResult counterfactualResult =
                counterfactualExplainer.explainAsync(prediction, model, captureIntermediateIds.andThen(captureExecutionIds))
                        .get(Config.INSTANCE.getAsyncTimeout(), Config.INSTANCE.getAsyncTimeUnit());

        for (CounterfactualEntity entity : counterfactualResult.getEntities()) {
            logger.debug("Entity: {}", entity);
        }

        // all intermediate Ids must be distinct
        assertEquals((int) intermediateIds.stream().distinct().count(), intermediateIds.size());
        assertEquals(1, (int) executionIds.stream().distinct().count());
        assertEquals(executionIds.get(0), executionId);
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2 })
    void testFinalUniqueIds(int seed) throws ExecutionException, InterruptedException, TimeoutException {
        Random random = new Random();
        random.setSeed(seed);

        final List<Output> goal = new ArrayList<>();

        List<Feature> features = List.of(
                FeatureFactory.newNumericalFeature("f-num1", 10.0,
                        NumericalFeatureDomain.create(0, 20)));

        PredictionProvider model = TestModels.getFeaturePassModel(0);

        final TerminationConfig terminationConfig =
                new TerminationConfig().withScoreCalculationCountLimit(100_000L);
        final SolverConfig solverConfig = SolverConfigBuilder
                .builder().withTerminationConfig(terminationConfig).build();

        solverConfig.setRandomSeed((long) seed);
        solverConfig.setEnvironmentMode(EnvironmentMode.REPRODUCIBLE);

        final List<UUID> intermediateIds = new ArrayList<>();
        final List<UUID> executionIds = new ArrayList<>();

        final Consumer<CounterfactualResult> captureIntermediateIds = counterfactual -> {
            intermediateIds.add(counterfactual.getSolutionId());
        };

        final Consumer<CounterfactualResult> captureExecutionIds = counterfactual -> {
            executionIds.add(counterfactual.getExecutionId());
        };

        final CounterfactualConfig counterfactualConfig = new CounterfactualConfig().withSolverConfig(solverConfig);
        solverConfig.withEasyScoreCalculatorClass(MockCounterFactualScoreCalculator.class);
        final CounterfactualExplainer counterfactualExplainer =
                new CounterfactualExplainer(counterfactualConfig);

        PredictionInput input = new PredictionInput(features);
        PredictionOutput output = new PredictionOutput(goal);
        final UUID executionId = UUID.randomUUID();
        Prediction prediction = new CounterfactualPrediction(input,
                output,
                null,
                executionId,
                null);
        final CounterfactualResult counterfactualResult =
                counterfactualExplainer.explainAsync(prediction, model, captureIntermediateIds.andThen(captureExecutionIds))
                        .get(Config.INSTANCE.getAsyncTimeout(), Config.INSTANCE.getAsyncTimeUnit());

        for (CounterfactualEntity entity : counterfactualResult.getEntities()) {
            logger.debug("Entity: {}", entity);
        }

        // All intermediate ids should be unique
        assertEquals((int) intermediateIds.stream().distinct().count(), intermediateIds.size());
        // There should be at least one intermediate id
        assertTrue(intermediateIds.size() > 0);
        // There should be at least one execution id
        assertTrue(executionIds.size() > 0);
        // We should have the same number of execution ids as intermediate ids (captured from intermediate results)
        assertEquals(executionIds.size(), intermediateIds.size());
        // All execution ids should be the same
        assertEquals(1, (int) executionIds.stream().distinct().count());
        // The last intermediate id must be different from the final result id
        assertNotEquals(intermediateIds.get(intermediateIds.size() - 1), counterfactualResult.getSolutionId());
        // Captured execution ids should be the same as the one provided
        assertEquals(executionIds.get(0), executionId);
    }

    /**
     * The test rationale is to find the solution to (f-num1 + f-num2 = 10), for f-num1 with an initial
     * value of 0 and f-num2 with an initial value of 5 and both varying in [0, 10].
     * All the possible solutions will have the same distance, but the sparsity
     * criteria will select the ones which leave one of the inputs (either f-num1 or f-num2) unchanged.
     *
     * @param seed
     * @throws ExecutionException
     * @throws InterruptedException
     * @throws TimeoutException
     */
    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3, 4 })
    void testSparsity(int seed)
            throws ExecutionException, InterruptedException, TimeoutException {
        Random random = new Random();
        random.setSeed(seed);
        final List<Output> goal = List.of(new Output("inside", Type.BOOLEAN, new Value(true), 0.0));

        List<Feature> features = new ArrayList<>();
        features.add(FeatureFactory.newNumericalFeature("f-num1", 0, NumericalFeatureDomain.create(0, 10)));
        features.add(FeatureFactory.newNumericalFeature("f-num2", 5, NumericalFeatureDomain.create(0, 10)));

        final double center = 10.0;
        final double epsilon = 0.1;

        final CounterfactualResult result =
                CounterfactualUtils.runCounterfactualSearch((long) seed, goal, features,
                        TestModels.getSumThresholdModel(center, epsilon),
                        DEFAULT_GOAL_THRESHOLD);

        assertTrue(!result.getEntities().get(0).isChanged() || !result.getEntities().get(1).isChanged());
        assertTrue(result.isValid());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testTerminationSpentLimitWhenDefined() throws ExecutionException, InterruptedException, TimeoutException {
        ArgumentCaptor<SolverConfig> solverConfigArgumentCaptor = ArgumentCaptor.forClass(SolverConfig.class);

        mockExplainerInvocation(mock(Consumer.class), MAX_RUNNING_TIME_SECONDS);

        verify(solverManagerFactory).apply(solverConfigArgumentCaptor.capture());
        SolverConfig solverConfig = solverConfigArgumentCaptor.getValue();

        assertEquals(MAX_RUNNING_TIME_SECONDS, solverConfig.getTerminationConfig().getSpentLimit().getSeconds());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testTerminationSpentLimitWhenUndefined() throws ExecutionException, InterruptedException, TimeoutException {
        ArgumentCaptor<SolverConfig> solverConfigArgumentCaptor = ArgumentCaptor.forClass(SolverConfig.class);

        mockExplainerInvocation(mock(Consumer.class), null);

        verify(solverManagerFactory).apply(solverConfigArgumentCaptor.capture());
        SolverConfig solverConfig = solverConfigArgumentCaptor.getValue();

        assertNull(solverConfig.getTerminationConfig().getSecondsSpentLimit());
    }

    @SuppressWarnings("unchecked")
    CounterfactualResult mockExplainerInvocation(Consumer<CounterfactualResult> intermediateResultsConsumer,
            Long maxRunningTimeSeconds) throws ExecutionException, InterruptedException, TimeoutException {
        //Mock SolverManager and SolverJob to guarantee deterministic test behaviour
        SolverJob<CounterfactualSolution, UUID> solverJob = mock(SolverJob.class);
        CounterfactualSolution solution = mock(CounterfactualSolution.class);
        BendableBigDecimalScore score = BendableBigDecimalScore.zero(0, 0);
        when(solverManager.solveAndListen(any(), any(), any(), any())).thenReturn(solverJob);
        when(solverJob.getFinalBestSolution()).thenReturn(solution);
        when(solution.getScore()).thenReturn(score);

        when(solverManagerFactory.apply(any())).thenReturn(solverManager);

        //Setup Explainer
        final CounterfactualConfig counterfactualConfig =
                new CounterfactualConfig().withSolverManagerFactory(solverManagerFactory);
        final CounterfactualExplainer counterfactualExplainer =
                new CounterfactualExplainer(counterfactualConfig);

        //Setup mock model, what it does is not important
        Prediction prediction = new CounterfactualPrediction(new PredictionInput(Collections.emptyList()),
                new PredictionOutput(Collections.emptyList()),
                null,
                UUID.randomUUID(),
                maxRunningTimeSeconds);

        return counterfactualExplainer.explainAsync(prediction,
                (List<PredictionInput> inputs) -> CompletableFuture.completedFuture(Collections.emptyList()),
                intermediateResultsConsumer)
                .get(Config.INSTANCE.getAsyncTimeout(),
                        Config.INSTANCE.getAsyncTimeUnit());
    }

    @Test
    void testAsTable()
            throws ExecutionException, InterruptedException, TimeoutException {
        Random random = new Random();
        random.setSeed(0L);
        List<Feature> features = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            if (i == 2) {
                features.add(new Feature(
                        "Feature " + i,
                        Type.CATEGORICAL,
                        new Value("A"),
                        false,
                        CategoricalFeatureDomain.create(List.of("A", "B"))));
            } else {
                features.add(
                        new Feature(
                                "Feature " + i,
                                Type.NUMBER,
                                new Value(i),
                                false,
                                NumericalFeatureDomain.create(-5, 5)));
            }
        }
        PredictionProvider model = TestModels.getTwoOutputSemiCategoricalModel(2);

        final List<Output> goal = List.of(
                new Output("Semi-Categorical", Type.NUMBER, new Value(1), 0.0),
                new Output("Semi-Categorical*2", Type.NUMBER, new Value(2), 0.0));
        List<Output> originalOutputs = model.predictAsync(List.of(new PredictionInput(features))).get().get(0).getOutputs();

        final CounterfactualResult result = CounterfactualUtils.runCounterfactualSearch(0L, goal, features, model, .01, 100_000);
        String resultString = result.asTable(originalOutputs, goal);
        assertTrue(resultString.contains("Counterfactual Search Results"));
        assertTrue(resultString.contains("Meets Validity Criteria?"));
        assertTrue(resultString.contains("Feature 3"));
        assertTrue(resultString.contains("[A, B]"));
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2 })
    void testCounterfactualCategoricalFeature(int seed) throws ExecutionException, InterruptedException, TimeoutException {
        Random random = new Random();
        random.setSeed(seed);

        final List<Output> goal = List.of(new Output("sum-but1", Type.NUMBER, new Value(10), 0.0d));
        List<Feature> features = new LinkedList<>();
        features.add(FeatureFactory.newCategoricalNumericalFeature("f-num1", 0,
                CategoricalNumericalFeatureDomain.create(0, 1, 5)));
        features.add(FeatureFactory.newNumericalFeature("f-num3", 1.0, NumericalFeatureDomain.create(0.0, 10.0)));
        features.add(FeatureFactory.newCategoricalNumericalFeature("f-num4", 0,
                CategoricalNumericalFeatureDomain.create(5, 6, 7)));

        final PredictionProvider model = TestModels.getSumSkipModel(1);
        final CounterfactualResult result =
                CounterfactualUtils.runCounterfactualSearch((long) seed, goal, features,
                        model,
                        DEFAULT_GOAL_THRESHOLD);

        assertTrue(result.isValid());
        assertEquals(10, result.getOutput().get(0).getOutputs().get(0).getValue().asNumber());
        assertEquals(5, result.getEntities().get(0).asFeature().getValue().asNumber());
        assertEquals(5, result.getEntities().get(2).asFeature().getValue().asNumber());
        assertTrue(result.getEntities().get(0) instanceof CategoricalNumericalEntity);
        assertTrue(result.getEntities().get(2) instanceof CategoricalNumericalEntity);
        assertEquals(Type.CATEGORICAL, result.getEntities().get(0).asFeature().getType());
        assertEquals(Type.CATEGORICAL, result.getEntities().get(0).asFeature().getType());
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3, 4 })
    void testLinearModelSolve(int seed) throws ExecutionException, InterruptedException, TimeoutException {

        final double[] featureValues = new double[] { -0.04822564522107575, 2.0912726657731104, 5.368920447474639, 0.7460348559645964, 3.6228232398513613 };

        final List<Feature> fs = new ArrayList<>();

        for (int i = 0; i < 5; i++) {
            fs.add(new Feature(String.valueOf(i), Type.NUMBER, new Value(featureValues[i]), false, NumericalFeatureDomain.create(-5, 5)));
        }

        final PredictionProvider model = TestModels.getLinearModel(new double[] { 5., 0., 1., 25., -5. });

        final List<Output> goal = List.of(new Output("linear-sum", Type.NUMBER, new Value(0.), 1d));

        final CounterfactualResult result = CounterfactualUtils.runCounterfactualSearch((long) seed, goal, fs, model, .01);

        assertTrue(result.isValid());
        assertEquals(0.0, result.getOutput().get(0).getOutputs().get(0).getValue().asNumber(), .01);

    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2 })
    void testDynamicGoals(int seed)
            throws ExecutionException, InterruptedException, TimeoutException {
        Random random = new Random();
        random.setSeed(seed);

        List<Feature> features = new LinkedList<>();
        features.add(FeatureFactory.newNumericalFeature("f-num1", 100.0, NumericalFeatureDomain.create(0.0, 1000.0)));
        features.add(FeatureFactory.newNumericalFeature("f-num2", 100.0, NumericalFeatureDomain.create(0.0, 1000.0)));
        features.add(FeatureFactory.newNumericalFeature("f-num3", 100.0, NumericalFeatureDomain.create(0.0, 1000.0)));
        features.add(FeatureFactory.newNumericalFeature("f-num4", 100.0, NumericalFeatureDomain.create(0.0, 1000.0)));

        final PredictionProvider model = TestModels.getSumSkipModel(1);

        CounterfactualGoalCriteria goalFunction = (outputs) -> {
            double sum = outputs.stream().mapToDouble(o -> o.getValue().asNumber()).sum();
            if ((sum >= 500.0) && (sum <= 700.0)) {
                return GoalScore.getExactMatch();
            } else {
                return GoalScore.create(Math.abs(sum - 600.0), 0.0);
            }
        };

        final CounterfactualResult result =
                CounterfactualUtils.runCounterfactualSearch((long) seed, features,
                        model,
                        DEFAULT_GOAL_THRESHOLD, goalFunction, 100_000);

        final List<CounterfactualEntity> counterfactualEntities = result.getEntities();

        final double sum = counterfactualEntities.stream().mapToDouble(e -> e.asFeature().getValue().asNumber()).sum();
        assertTrue(result.isValid());
        assertTrue(sum >= 500 && sum <= 700);
    }

}
