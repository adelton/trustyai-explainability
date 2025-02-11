package org.kie.trustyai.service.endpoints.service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.jboss.logging.Logger;
import org.kie.trustyai.explainability.model.Dataframe;
import org.kie.trustyai.service.config.metrics.MetricsConfig;
import org.kie.trustyai.service.data.DataSource;
import org.kie.trustyai.service.data.exceptions.DataframeCreateException;
import org.kie.trustyai.service.data.exceptions.StorageReadException;
import org.kie.trustyai.service.data.metadata.Metadata;
import org.kie.trustyai.service.payloads.metrics.BaseMetricRequest;
import org.kie.trustyai.service.payloads.service.DataTagging;
import org.kie.trustyai.service.payloads.service.NameMapping;
import org.kie.trustyai.service.payloads.service.Schema;
import org.kie.trustyai.service.payloads.service.ServiceMetadata;
import org.kie.trustyai.service.prometheus.PrometheusScheduler;
import org.kie.trustyai.service.validators.generic.GenericValidationUtils;

import com.fasterxml.jackson.core.JsonProcessingException;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
@Path("/info")
public class ServiceMetadataEndpoint {

    private static final Logger LOG = Logger.getLogger(ServiceMetadataEndpoint.class);
    @Inject
    Instance<DataSource> dataSource;

    @Inject
    PrometheusScheduler scheduler;

    @Inject
    MetricsConfig metricsConfig;

    ServiceMetadataEndpoint() {

    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response serviceInfo() throws JsonProcessingException {

        final List<ServiceMetadata> serviceMetadataList = new ArrayList<>();

        for (String modelId : dataSource.get().getKnownModels()) {
            final ServiceMetadata serviceMetadata = new ServiceMetadata();

            for (Map.Entry<String, ConcurrentHashMap<UUID, BaseMetricRequest>> metricDict : scheduler.getAllRequests().entrySet()) {
                metricDict.getValue().values().forEach(metric -> {
                    if (metric.getModelId().equals(modelId)) {
                        final String metricName = metricDict.getKey();
                        serviceMetadata.getMetrics().scheduledMetadata.setCount(metricName, serviceMetadata.getMetrics().scheduledMetadata.getCount(metricName) + 1);
                    }
                });
            }

            try {
                final Metadata metadata = dataSource.get().getMetadata(modelId);
                serviceMetadata.setData(metadata);
            } catch (DataframeCreateException | StorageReadException | NullPointerException e) {
                LOG.warn("Problem creating dataframe: " + e.getMessage(), e);
            }

            serviceMetadataList.add(serviceMetadata);

        }

        return Response.ok(serviceMetadataList).build();

    }

    @POST
    @Path("/tags")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response labelSchema(DataTagging dataTagging) throws JsonProcessingException {

        if (!dataSource.get().getKnownModels().contains(dataTagging.getModelId())) {
            return Response.serverError()
                    .status(Response.Status.BAD_REQUEST)
                    .entity("Model ID " + dataTagging.getModelId() + " does not exist in TrustyAI metadata.")
                    .build();
        }

        try {
            HashMap<String, List<List<Integer>>> tagMapping = new HashMap<>();
            List<String> tagErrors = new ArrayList<>();
            for (String tag : dataTagging.getDataTagging().keySet()) {
                Optional<String> tagValidationErrorMessage = GenericValidationUtils.validateDataTag(tag);
                tagValidationErrorMessage.ifPresent(tagErrors::add);
                tagMapping.put(tag, dataTagging.getDataTagging().get(tag));
            }

            if (!tagErrors.isEmpty()) {
                return Response.serverError().entity(String.join(", ", tagErrors)).status(Response.Status.BAD_REQUEST).build();
            }

            Dataframe df = dataSource.get().getDataframe(dataTagging.getModelId());
            df.tagDataPoints(tagMapping);
            dataSource.get().saveDataframe(df, dataTagging.getModelId(), true);
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            return Response.serverError()
                    .status(Response.Status.BAD_REQUEST)
                    .entity(e.getMessage())
                    .build();
        }

        return Response.ok().entity("Datapoints successfully tagged.").build();
    }

    @POST
    @Path("/names")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response labelSchema(NameMapping nameMapping) throws JsonProcessingException {

        if (!dataSource.get().getKnownModels().contains(nameMapping.getModelId())) {
            return Response.serverError()
                    .status(Response.Status.BAD_REQUEST)
                    .entity("Model ID " + nameMapping.getModelId() + " does not exist in TrustyAI metadata.")
                    .build();
        }
        final Metadata metadata = dataSource.get().getMetadata(nameMapping.getModelId());

        // validation
        Schema inputSchema = metadata.getInputSchema();
        Set<String> inputKeySet = inputSchema.getItems().keySet();
        Set<String> nameMappingInputKeySet = nameMapping.getInputMapping().keySet();

        Schema outputSchema = metadata.getOutputSchema();
        Set<String> outputKeySet = outputSchema.getItems().keySet();
        Set<String> nameMappingOutputKeySet = nameMapping.getOutputMapping().keySet();

        if (!inputKeySet.containsAll(nameMappingInputKeySet)) {
            Set<String> copyNameMapping = new HashSet<>(nameMappingInputKeySet);
            copyNameMapping.removeAll(inputKeySet);
            return Response.serverError()
                    .status(Response.Status.BAD_REQUEST)
                    .entity("Not all mapped input fields exist in model metadata, input features " + copyNameMapping + " do not exist")
                    .build();
        }

        if (!outputKeySet.containsAll(nameMappingOutputKeySet)) {
            Set<String> copyNameMapping = new HashSet<>(nameMappingOutputKeySet);
            copyNameMapping.removeAll(outputKeySet);
            return Response.serverError()
                    .status(Response.Status.BAD_REQUEST)
                    .entity("Not all mapped output fields exist in model metadata, output fields " + copyNameMapping + " do not exist")
                    .build();
        }

        inputSchema.setNameMapping(nameMapping.getInputMapping());
        outputSchema.setNameMapping(nameMapping.getOutputMapping());
        dataSource.get().saveMetadata(metadata, metadata.getModelId());

        return Response.ok().entity("Feature and output name mapping successfully applied.").build();
    }

}
