package org.kie.trustyai.service.endpoints.metrics.fairness.group;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.kie.trustyai.explainability.model.Dataframe;
import org.kie.trustyai.explainability.model.Output;
import org.kie.trustyai.explainability.model.Type;
import org.kie.trustyai.explainability.model.Value;
import org.kie.trustyai.metrics.fairness.FairnessDefinitions;
import org.kie.trustyai.metrics.fairness.group.DisparateImpactRatio;
import org.kie.trustyai.service.data.cache.MetricCalculationCacheKeyGen;
import org.kie.trustyai.service.data.exceptions.MetricCalculationException;
import org.kie.trustyai.service.payloads.PayloadConverter;
import org.kie.trustyai.service.payloads.metrics.BaseMetricRequest;
import org.kie.trustyai.service.payloads.metrics.MetricThreshold;
import org.kie.trustyai.service.payloads.metrics.fairness.group.GroupMetricRequest;
import org.kie.trustyai.service.prometheus.MetricValueCarrier;
import org.kie.trustyai.service.validators.metrics.ValidReconciledMetricRequest;
import org.kie.trustyai.service.validators.metrics.fairness.group.ValidGroupMetricRequest;

import io.quarkus.cache.CacheResult;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Path;

@ApplicationScoped
@Tag(name = "Disparate Impact Ratio Endpoint", description = "Disparate Impact Ratio (DIR) measures imbalances in " +
        "classifications by calculating the ratio between the proportion of the majority and protected classes getting" +
        " a particular outcome.")
@Path("/metrics/group/fairness/dir")
public class DisparateImpactRatioEndpoint extends GroupEndpoint {
    @Override
    public MetricThreshold thresholdFunction(Number delta, MetricValueCarrier metricValue) {
        if (delta == null) {
            return new MetricThreshold(
                    metricsConfig.dir().thresholdLower(),
                    metricsConfig.dir().thresholdUpper(),
                    metricValue.getValue());
        } else {
            return new MetricThreshold(
                    1 - delta.doubleValue(),
                    1 + delta.doubleValue(),
                    metricValue.getValue());
        }
    }

    @Override
    public String specificDefinitionFunction(String outcomeName, Value favorableOutcomeAttr, String protectedAttribute, String privileged, String unprivileged, MetricValueCarrier metricValue) {
        return FairnessDefinitions.defineGroupDisparateImpactRatio(
                protectedAttribute,
                privileged,
                unprivileged,
                outcomeName,
                favorableOutcomeAttr,
                metricValue.getValue());
    }

    @Override
    @CacheResult(cacheName = "metrics-calculator-dir", keyGenerator = MetricCalculationCacheKeyGen.class)
    public MetricValueCarrier calculate(Dataframe dataframe, @ValidReconciledMetricRequest BaseMetricRequest request) {
        LOG.debug("Cache miss. Calculating metric for " + request.getModelId());
        @ValidGroupMetricRequest
        GroupMetricRequest gmRequest = (GroupMetricRequest) request;
        try {
            final int protectedIndex = dataframe.getColumnNames().indexOf(gmRequest.getProtectedAttribute());

            final Value privilegedAttr = PayloadConverter.convertToValue(gmRequest.getPrivilegedAttribute().getReconciledType().get());

            final Dataframe privileged = dataframe.filterByColumnValue(protectedIndex,
                    value -> value.equals(privilegedAttr));
            final Value unprivilegedAttr = PayloadConverter.convertToValue(gmRequest.getUnprivilegedAttribute().getReconciledType().get());
            final Dataframe unprivileged = dataframe.filterByColumnValue(protectedIndex,
                    value -> value.equals(unprivilegedAttr));
            final Value favorableOutcomeAttr = PayloadConverter.convertToValue(gmRequest.getFavorableOutcome().getReconciledType().get());
            final Type favorableOutcomeAttrType = PayloadConverter.convertToType(gmRequest.getFavorableOutcome().getReconciledType().get().getType());
            return new MetricValueCarrier(
                    DisparateImpactRatio.calculate(
                            privileged,
                            unprivileged,
                            List.of(new Output(gmRequest.getOutcomeName(), favorableOutcomeAttrType, favorableOutcomeAttr, 1.0))));
        } catch (Exception e) {
            throw new MetricCalculationException(e.getMessage(), e);
        }
    }

    @Override
    public String getGeneralDefinition() {
        return FairnessDefinitions.defineGroupDisparateImpactRatio();
    }

    public DisparateImpactRatioEndpoint() {
        super("DIR");
    }
}
