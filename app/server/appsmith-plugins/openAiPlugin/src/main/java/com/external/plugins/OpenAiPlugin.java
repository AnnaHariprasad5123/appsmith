package com.external.plugins;

import com.appsmith.external.dtos.ExecuteActionDTO;
import com.appsmith.external.exceptions.pluginExceptions.AppsmithPluginError;
import com.appsmith.external.exceptions.pluginExceptions.AppsmithPluginException;
import com.appsmith.external.helpers.restApiUtils.connections.APIConnection;
import com.appsmith.external.helpers.restApiUtils.helpers.RequestCaptureFilter;
import com.appsmith.external.models.ActionConfiguration;
import com.appsmith.external.models.ActionExecutionRequest;
import com.appsmith.external.models.ActionExecutionResult;
import com.appsmith.external.models.BearerTokenAuth;
import com.appsmith.external.models.DatasourceConfiguration;
import com.appsmith.external.models.DatasourceTestResult;
import com.appsmith.external.models.TriggerRequestDTO;
import com.appsmith.external.models.TriggerResultDTO;
import com.appsmith.external.plugins.BasePlugin;
import com.appsmith.external.plugins.BaseRestApiPluginExecutor;
import com.appsmith.external.services.SharedConfig;
import com.external.plugins.commands.OpenAICommand;
import com.external.plugins.models.OpenAIRequestDTO;
import com.external.plugins.utils.OpenAIMethodStrategy;
import com.external.plugins.utils.RequestUtils;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.pf4j.PluginWrapper;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.external.plugins.constants.OpenAIConstants.BODY;
import static com.external.plugins.constants.OpenAIConstants.DATA;
import static com.external.plugins.constants.OpenAIConstants.ID;
import static com.external.plugins.constants.OpenAIConstants.MODEL;
import static com.external.plugins.constants.OpenAIErrorMessages.QUERY_FAILED_TO_EXECUTE;

@Slf4j
public class OpenAiPlugin extends BasePlugin {

    public OpenAiPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    public static class OpenAiPluginExecutor extends BaseRestApiPluginExecutor {

        private static final Gson gson = new Gson();
        private static final Cache<String, JSONObject> modelResponseCache =
                CacheBuilder.newBuilder().expireAfterWrite(1, TimeUnit.DAYS).build();

        public OpenAiPluginExecutor(SharedConfig config) {
            super(config);
        }

        @Override
        public Mono<ActionExecutionResult> executeParameterized(
                APIConnection connection,
                ExecuteActionDTO executeActionDTO,
                DatasourceConfiguration datasourceConfiguration,
                ActionConfiguration actionConfiguration) {

            String printMessage =
                    Thread.currentThread().getName() + ": executeParameterized() called for OpenAI plugin.";
            log.debug(printMessage);
            // Get prompt from action configuration
            List<Map.Entry<String, String>> parameters = new ArrayList<>();

            prepareConfigurationsForExecution(executeActionDTO, actionConfiguration, datasourceConfiguration);
            // Filter out any empty headers
            headerUtils.removeEmptyHeaders(actionConfiguration);
            headerUtils.setHeaderFromAutoGeneratedHeaders(actionConfiguration);

            return this.executeCommon(connection, datasourceConfiguration, actionConfiguration, parameters);
        }

        public Mono<ActionExecutionResult> executeCommon(
                APIConnection apiConnection,
                DatasourceConfiguration datasourceConfiguration,
                ActionConfiguration actionConfiguration,
                List<Map.Entry<String, String>> insertedParams) {

            String printMessage = Thread.currentThread().getName() + ": executeCommon() called for OpenAI plugin.";
            log.debug(printMessage);
            // Initializing object for error condition
            ActionExecutionResult errorResult = new ActionExecutionResult();
            initUtils.initializeResponseWithError(errorResult);

            // Find the right execution command
            OpenAICommand openAICommand = OpenAIMethodStrategy.selectExecutionMethod(actionConfiguration, gson);

            // morph the essentials
            OpenAIRequestDTO openAIRequestDTO = openAICommand.makeRequestBody(actionConfiguration);
            URI uri = openAICommand.createExecutionUri();
            HttpMethod httpMethod = openAICommand.getExecutionMethod();
            ActionExecutionRequest actionExecutionRequest =
                    RequestCaptureFilter.populateRequestFields(actionConfiguration, uri, insertedParams, objectMapper);

            // Authentication will already be valid at this point
            final BearerTokenAuth bearerTokenAuth = (BearerTokenAuth) datasourceConfiguration.getAuthentication();
            assert (bearerTokenAuth.getBearerToken() != null);

            return RequestUtils.makeRequest(httpMethod, uri, bearerTokenAuth, BodyInserters.fromValue(openAIRequestDTO))
                    .flatMap(responseEntity -> {
                        HttpStatusCode statusCode = responseEntity.getStatusCode();

                        ActionExecutionResult actionExecutionResult = new ActionExecutionResult();
                        actionExecutionResult.setRequest(actionExecutionRequest);
                        actionExecutionResult.setStatusCode(statusCode.toString());

                        if (HttpStatusCode.valueOf(401).isSameCodeAs(statusCode)) {
                            actionExecutionResult.setIsExecutionSuccess(false);
                            String errorMessage = "";
                            if (responseEntity.getBody() != null && responseEntity.getBody().length > 0) {
                                errorMessage = new String(responseEntity.getBody());
                            }
                            actionExecutionResult.setErrorInfo(new AppsmithPluginException(
                                    AppsmithPluginError.PLUGIN_DATASOURCE_AUTHENTICATION_ERROR, errorMessage));
                            return Mono.just(actionExecutionResult);
                        }

                        if (statusCode.is4xxClientError()) {
                            actionExecutionResult.setIsExecutionSuccess(false);
                            String errorMessage = "";
                            if (responseEntity.getBody() != null && responseEntity.getBody().length > 0) {
                                errorMessage = new String(responseEntity.getBody());
                            }
                            actionExecutionResult.setErrorInfo(new AppsmithPluginException(
                                    AppsmithPluginError.PLUGIN_DATASOURCE_ERROR, errorMessage));
                            return Mono.just(actionExecutionResult);
                        }

                        Object body;
                        try {
                            body = objectMapper.readValue(responseEntity.getBody(), Object.class);
                            actionExecutionResult.setBody(body);
                        } catch (IOException ex) {
                            actionExecutionResult.setIsExecutionSuccess(false);
                            actionExecutionResult.setErrorInfo(new AppsmithPluginException(
                                    AppsmithPluginError.PLUGIN_JSON_PARSE_ERROR, BODY, ex.getMessage()));
                            return Mono.just(actionExecutionResult);
                        }

                        if (!statusCode.is2xxSuccessful()) {
                            actionExecutionResult.setIsExecutionSuccess(false);
                            actionExecutionResult.setErrorInfo(new AppsmithPluginException(
                                    AppsmithPluginError.PLUGIN_ERROR, QUERY_FAILED_TO_EXECUTE, body));
                            return Mono.just(actionExecutionResult);
                        }

                        actionExecutionResult.setIsExecutionSuccess(true);

                        return Mono.just(actionExecutionResult);
                    })
                    .onErrorResume(error -> {
                        errorResult.setIsExecutionSuccess(false);
                        log.debug(
                                "An error has occurred while trying to run the open API query command with error {}",
                                error.getStackTrace());
                        if (!(error instanceof AppsmithPluginException)) {
                            error = new AppsmithPluginException(
                                    AppsmithPluginError.PLUGIN_ERROR, error.getMessage(), error);
                        }
                        errorResult.setErrorInfo(error);
                        return Mono.just(errorResult);
                    });
        }

        private String cacheKey(String bearerToken) {
            return sha256(bearerToken);
        }

        private String sha256(String base) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(base.getBytes(StandardCharsets.UTF_8));
                StringBuilder hexString = new StringBuilder();

                for (byte b : hash) {
                    String hex = Integer.toHexString(0xff & b);
                    if (hex.length() == 1) hexString.append('0');
                    hexString.append(hex);
                }

                return hexString.toString();
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }

        @Override
        public Set<String> validateDatasource(DatasourceConfiguration datasourceConfiguration) {
            String printMessage = Thread.currentThread().getName() + ": validateDatasource() called for OpenAI plugin.";
            log.debug(printMessage);
            return RequestUtils.validateBearerTokenDatasource(datasourceConfiguration);
        }

        @Override
        public Mono<TriggerResultDTO> trigger(
                APIConnection connection, DatasourceConfiguration datasourceConfiguration, TriggerRequestDTO request) {

            String printMessage = Thread.currentThread().getName() + ": trigger() called for OpenAI plugin.";
            log.debug(printMessage);
            // Authentication will already be valid at this point
            final BearerTokenAuth bearerTokenAuth = (BearerTokenAuth) datasourceConfiguration.getAuthentication();
            assert (bearerTokenAuth.getBearerToken() != null);
            OpenAICommand openAICommand = OpenAIMethodStrategy.selectTriggerMethod(request, gson);
            HttpMethod httpMethod = openAICommand.getTriggerHTTPMethod();
            URI uri = openAICommand.createTriggerUri();

            String cacheKey = cacheKey(bearerTokenAuth.getBearerToken());
            JSONObject modelsResponse = modelResponseCache.getIfPresent(cacheKey);

            Mono<JSONObject> responseMono;
            if (modelsResponse != null) {
                responseMono = Mono.just(modelsResponse);
            } else {
                responseMono = RequestUtils.makeRequest(httpMethod, uri, bearerTokenAuth, BodyInserters.empty())
                        .flatMap(responseEntity -> {
                            if (responseEntity.getStatusCode().is4xxClientError()) {
                                return Mono.error(new AppsmithPluginException(
                                        AppsmithPluginError.PLUGIN_DATASOURCE_AUTHENTICATION_ERROR));
                            }

                            if (!responseEntity.getStatusCode().is2xxSuccessful()) {
                                return Mono.error(
                                        new AppsmithPluginException(AppsmithPluginError.PLUGIN_GET_STRUCTURE_ERROR));
                            }
                            JSONObject responseObject = new JSONObject(new String(responseEntity.getBody()));
                            modelResponseCache.put(cacheKey, responseObject);
                            // link to get response data https://platform.openai.com/docs/api-reference/models/list
                            return Mono.just(responseObject);
                        });
            }

            return responseMono
                    .map(jsonObject -> {
                        if (!jsonObject.has(DATA) && jsonObject.get(DATA) instanceof JSONArray) {
                            // let's throw some error.
                            throw Exceptions.propagate(
                                    new AppsmithPluginException(AppsmithPluginError.PLUGIN_GET_STRUCTURE_ERROR));
                        }

                        List<String> compatibleModels = new ArrayList<>();
                        Map<String, JSONObject> modelsMap = new HashMap<>();
                        JSONArray modelList = jsonObject.getJSONArray(DATA);
                        int modelListIndex = 0;
                        while (modelListIndex < modelList.length()) {
                            JSONObject model = modelList.getJSONObject(modelListIndex);
                            if (!model.has(ID)) {
                                continue;
                            }

                            if (openAICommand.isModelCompatible(model)) {
                                String id = model.getString(ID);
                                compatibleModels.add(id);
                                modelsMap.put(id, model);
                            }

                            modelListIndex += 1;
                        }
                        // sort models alphabetically
                        return compatibleModels.stream()
                                .sorted()
                                .map(model -> openAICommand.getModelMap(modelsMap.get(model)))
                                .collect(Collectors.toList());
                    })
                    .map(TriggerResultDTO::new);
        }

        @Override
        public Mono<DatasourceTestResult> testDatasource(DatasourceConfiguration datasourceConfiguration) {
            String printMessage = Thread.currentThread().getName() + ": testDatasource() called for OpenAI plugin.";
            log.debug(printMessage);
            final BearerTokenAuth bearerTokenAuth = (BearerTokenAuth) datasourceConfiguration.getAuthentication();

            HttpMethod httpMethod = HttpMethod.GET;
            URI uri = RequestUtils.createUriFromCommand(MODEL);

            return RequestUtils.makeRequest(httpMethod, uri, bearerTokenAuth, BodyInserters.empty())
                    .map(responseEntity -> {
                        if (responseEntity.getStatusCode().is2xxSuccessful()) {
                            return new DatasourceTestResult();
                        }

                        AppsmithPluginException error =
                                new AppsmithPluginException(AppsmithPluginError.PLUGIN_DATASOURCE_AUTHENTICATION_ERROR);
                        return new DatasourceTestResult(error.getMessage());
                    })
                    .onErrorResume(error -> {
                        if (!(error instanceof AppsmithPluginException)) {
                            error = new AppsmithPluginException(
                                    AppsmithPluginError.PLUGIN_DATASOURCE_AUTHENTICATION_ERROR);
                        }
                        return Mono.just(new DatasourceTestResult(error.getMessage()));
                    });
        }
    }
}
