package dev.sunbirdrc.registry.helper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.gson.Gson;
import dev.sunbirdrc.pojos.ComponentHealthInfo;
import dev.sunbirdrc.pojos.HealthIndicator;
import dev.sunbirdrc.registry.middleware.util.JSONUtil;
import dev.sunbirdrc.registry.service.ISearchService;
import dev.sunbirdrc.registry.service.impl.RetryRestTemplate;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.util.*;

import static dev.sunbirdrc.registry.middleware.util.Constants.*;

@Component
public class DidHelper implements HealthIndicator {
    private static final Logger logger = LoggerFactory.getLogger(DidHelper.class);
    @Value("${did.healthCheckURL}")
    private String healthCheckUrl;
    @Value("${did.generateURL}")
    private String generateIdUrl;
    @Value("${did.resolveURL}")
    private String resolveIdUrl;

    private static final String authorSchemaName = "Issuer";
    private static final String didPropertyName = "did";

    @Autowired
    private RetryRestTemplate retryRestTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ISearchService searchService;
    @Autowired
    private RegistryHelper registryHelper;
    @Autowired
    private Gson gson;

    public String getDid(String name) throws Exception {
        try {
            return findDidForProperty("name", name);
        } catch (Exception e) {
            return findDidForProperty(didPropertyName, name);
        }
    }

    public String findDidForProperty(String propertyName, String value) throws Exception {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.set(ENTITY_TYPE, JsonNodeFactory.instance.arrayNode().add(authorSchemaName));
        ObjectNode filters = JsonNodeFactory.instance.objectNode();
        filters.set(propertyName, JsonNodeFactory.instance.objectNode().put("eq", value));
        payload.set(FILTERS, filters);
        JsonNode results = searchService.search(payload);
        if(results.get(authorSchemaName).isEmpty()) {
            throw new RuntimeException(String.format("%s %s not found in schema %s for property %s", propertyName, value, authorSchemaName, didPropertyName));
        }
        return results.get(authorSchemaName).get(0).get(didPropertyName).asText();
    }

    public String ensureDidForName(String name, String method) throws Exception {
        String did;
        try {
            did = getDid(name);
        } catch (Exception e) {
            did = this.generateDid(method, null);
            ObjectNode rootNode = objectMapper.createObjectNode();
            rootNode.set("name", JsonNodeFactory.instance.textNode(name));
            rootNode.set("did", JsonNodeFactory.instance.textNode(did));
            ObjectNode newRootNode = objectMapper.createObjectNode();
            newRootNode.set(authorSchemaName, rootNode);
            registryHelper.addEntity(newRootNode, null);
        }
        return did;
    }

    public String generateDid(String method, Map<String, Object> content) {
        Map<String, Object> requestMap = new HashMap<>();
        Map<String, Object> map = new HashMap<>();
        if(content != null) {
            map.putAll(content);
        }
        if(!map.containsKey("service")) {
            map.put("service", Collections.emptyList());
        }
        if(!map.containsKey("alsoKnownAs")) {
            map.put("alsoKnownAs", Collections.emptyList());
        }
        map.put("method", method);
        requestMap.put("content", Collections.singletonList(map));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(gson.toJson(requestMap), headers);
        try {
            ResponseEntity<String> response = retryRestTemplate.postForEntity(generateIdUrl, request);
            if (response.getStatusCode().is2xxSuccessful()) {
                JsonNode node = JSONUtil.convertStringJsonNode(response.getBody());
                return node.get(0).get("id").asText();
            }
        } catch (RestClientException | IOException e) {
            logger.error("Exception when checking the health of the Sunbird {} service: {}", getServiceName(), ExceptionUtils.getStackTrace(e));
        }
        return null;
    }

    public String resolveDid(String didId) {
        try {
            ResponseEntity<String> response = retryRestTemplate.getForEntity(resolveIdUrl + "/" + didId);
            if (response.getStatusCode().is2xxSuccessful()) {
                JsonNode node = JSONUtil.convertStringJsonNode(response.getBody());
                return node.get("id").asText();
            }
        } catch (RestClientException | IOException e) {
            logger.error("Exception when checking the health of the Sunbird {} service: {}", getServiceName(), ExceptionUtils.getStackTrace(e));
        }
        return null;
    }

    public String getServiceName() {
        return "DID_SERVICE";
    }

    @Override
    public ComponentHealthInfo getHealthInfo() {
        try {
            ResponseEntity<String> response = retryRestTemplate.getForEntity(healthCheckUrl);
            if (!StringUtils.isEmpty(response.getBody()) && JSONUtil.convertStringJsonNode(response.getBody()).get("status").asText().equalsIgnoreCase("UP")) {
                logger.debug("{} service running!", this.getServiceName());
                return new ComponentHealthInfo(getServiceName(), true);
            } else {
                return new ComponentHealthInfo(getServiceName(), false);
            }
        } catch (Exception e) {
            logger.error("Exception when checking the health of the Sunbird {} service: {}", getServiceName(), ExceptionUtils.getStackTrace(e));
            return new ComponentHealthInfo(getServiceName(), false, CONNECTION_FAILURE, e.getMessage());
        }
    }
}
