/*
 * Copyright 2017 Huawei Technologies Co., Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.servicecomb.core.definition.schema;

import java.util.HashSet;
import java.util.Set;

import javax.inject.Inject;

import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.servicecomb.core.definition.MicroserviceMeta;
import io.servicecomb.core.definition.SchemaMeta;
import io.servicecomb.core.definition.SchemaUtils;
import io.servicecomb.core.definition.loader.SchemaListenerManager;
import io.servicecomb.foundation.common.config.PaaSResourceUtils;
import io.servicecomb.serviceregistry.api.registry.Microservice;
import io.servicecomb.serviceregistry.client.RegistryClientFactory;
import io.servicecomb.serviceregistry.client.ServiceRegistryClient;
import io.swagger.models.Swagger;

@Component
public class ConsumerSchemaFactory extends AbstractSchemaFactory<ConsumerSchemaContext> {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConsumerSchemaFactory.class);

    @Inject
    protected SchemaListenerManager schemaListenerManager;

    private final Object lock = new Object();

    public void setSchemaListenerManager(SchemaListenerManager schemaListenerManager) {
        this.schemaListenerManager = schemaListenerManager;
    }

    // 透明rpc场景，因为每次指定schema调用，所以可以懒加载
    // 而rest场景，是根据path反向查找schema，所以必须所有的schema都存在才行
    // 两种场景可能都会来初始化，为避免管理复杂度，改为全部全量初始化
    //
    // 用于rest consumer注册的场景，此时启动流程已经完成，需要主动通知listener
    // microserviceName可能是本app内的微服务
    // 也可能是appid:name形式的其他app的微服务
    public MicroserviceMeta getOrCreateMicroserviceMeta(String microserviceName, String microserviceVersionRule) {
        MicroserviceMeta microserviceMeta = microserviceMetaManager.findValue(microserviceName);
        if (microserviceMeta != null) {
            return microserviceMeta;
        }

        // 极小概率事件，不必做microservice级的锁分解了
        synchronized (lock) {
            microserviceMeta = microserviceMetaManager.findValue(microserviceName);
            if (microserviceMeta != null) {
                return microserviceMeta;
            }

            // 获取指定服务中有哪些schemaId
            // 先取本地，再从服务中心取，如果服务中心取成功了，则将schema id合并处理
            microserviceMeta = new MicroserviceMeta(microserviceName);
            Set<String> schemaIds = findLocalSchemas(microserviceMeta);
            Microservice microservice =
                findMicroservice(microserviceMeta, microserviceVersionRule);
            if (microservice != null) {
                schemaIds.addAll(microservice.getSchemas());
            }

            getOrCreateConsumerSchema(microserviceMeta, schemaIds, microservice);

            microserviceMetaManager.register(microserviceName, microserviceMeta);
            schemaListenerManager.notifySchemaListener(microserviceMeta);
            return microserviceMeta;
        }

    }

    protected Set<String> findLocalSchemas(MicroserviceMeta microserviceMeta) {
        String resPath = generateSchemaPath(microserviceMeta.getName(), "*");
        Resource[] resArr = PaaSResourceUtils.getResources("classpath*:" + resPath);

        Set<String> schemaIds = new HashSet<>();
        for (Resource res : resArr) {
            String schemaId = FilenameUtils.getBaseName(res.getFilename());
            schemaIds.add(schemaId);
        }

        LOGGER.info("Found schema ids local, {}:{}:{}",
                microserviceMeta.getAppId(),
                microserviceMeta.getName(),
                schemaIds);
        return schemaIds;
    }

    protected Microservice findMicroservice(MicroserviceMeta microserviceMeta, String microserviceVersionRule) {
        String appId = microserviceMeta.getAppId();
        String microserviceName = microserviceMeta.getName();
        ServiceRegistryClient client = RegistryClientFactory.getRegistryClient();
        String microserviceId = client.getMicroserviceId(appId,
                microserviceMeta.getShortName(),
                microserviceVersionRule);
        if (StringUtils.isEmpty(microserviceId)) {
            LOGGER.error("can not get microservice id, {}:{}:{}", appId, microserviceName, microserviceVersionRule);
            return null;
        }

        Microservice microservice = client.getMicroservice(microserviceId);
        if (microservice == null) {
            LOGGER.error("can not get microservice, {}:{}:{}", appId, microserviceName, microserviceVersionRule);
            return null;
        }

        LOGGER.info("Found schema ids from service center, {}:{}:{}:{}",
                appId,
                microserviceName,
                microserviceVersionRule,
                microservice.getSchemas());
        return microservice;
    }

    // 允许consumerIntf与schemaId对应的interface原型不同，用于支持context类型的参数
    // consumerIntf为null，表示原型与契约相同
    // 如果从远程获取microservice失败，则microservice为null
    protected void getOrCreateConsumerSchema(MicroserviceMeta microserviceMeta, Set<String> schemaIds,
            Microservice microservice) {
        for (String schemaId : schemaIds) {
            ConsumerSchemaContext context = new ConsumerSchemaContext();
            context.setMicroserviceMeta(microserviceMeta);
            context.setMicroservice(microservice);
            context.setSchemaId(schemaId);
            context.setProviderClass(null);

            getOrCreateSchema(context);
        }
    }

    @Override
    protected SchemaMeta createSchema(ConsumerSchemaContext context) {
        // 尝试从规划的目录或服务中心加载契约
        Swagger swagger = loadSwagger(context);

        // 注册契约
        return schemaLoader.registerSchema(context.getMicroserviceMeta(), context.getSchemaId(), swagger);
    }

    @Override
    protected Swagger loadSwagger(ConsumerSchemaContext context) {
        Swagger swagger = super.loadSwagger(context);
        if (swagger != null) {
            return swagger;
        }

        if (context.getMicroservice() == null) {
            throw new Error(
                    String.format("no schema in local, and can not get microservice from service center, %s:%s",
                            context.getMicroserviceName(),
                            context.getSchemaId()));
        }

        ServiceRegistryClient client = RegistryClientFactory.getRegistryClient();
        String schemaContent = client.getSchema(context.getMicroservice().getServiceId(), context.getSchemaId());
        LOGGER.info("load schema from service center, microservice={}:{}:{}, schemaId={}, result={}",
                context.getMicroservice().getAppId(),
                context.getMicroservice().getServiceName(),
                context.getMicroservice().getVersion(),
                context.getSchemaId(),
                !StringUtils.isEmpty(schemaContent));
        if (schemaContent != null) {
            return SchemaUtils.parseSwagger(schemaContent);
        }

        throw new Error(
                String.format("no schema in local, and can not get schema from service center, %s:%s",
                        context.getMicroserviceName(),
                        context.getSchemaId()));
    }
}
