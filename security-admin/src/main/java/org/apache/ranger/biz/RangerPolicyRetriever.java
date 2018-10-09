/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.ranger.biz;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.ranger.authorization.utils.JsonUtils;
import org.apache.ranger.authorization.utils.StringUtil;
import org.apache.ranger.db.RangerDaoManager;
import org.apache.ranger.entity.XXPolicy;
import org.apache.ranger.entity.XXPortalUser;
import org.apache.ranger.entity.XXService;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerPolicy.RangerDataMaskPolicyItem;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItem;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItemAccess;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItemCondition;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyResource;
import org.apache.ranger.plugin.util.RangerPerfTracer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;


public class RangerPolicyRetriever {
	static final Log LOG      = LogFactory.getLog(RangerPolicyRetriever.class);
	static final Log PERF_LOG = RangerPerfTracer.getPerfLogger("db.RangerPolicyRetriever");

	private final RangerDaoManager  daoMgr;
	private final LookupCache       lookupCache = new LookupCache();

	private final PlatformTransactionManager  txManager;
	private final TransactionTemplate         txTemplate;

	public RangerPolicyRetriever(RangerDaoManager daoMgr, PlatformTransactionManager txManager) {
		this.daoMgr     = daoMgr;
		this.txManager  = txManager;
		if (this.txManager != null) {
			this.txTemplate = new TransactionTemplate(this.txManager);
			this.txTemplate.setReadOnly(true);
		} else {
			this.txTemplate = null;
		}
	}

	public RangerPolicyRetriever(RangerDaoManager daoMgr) {
		this.daoMgr      = daoMgr;
		this.txManager   = null;
		this.txTemplate  = null;
	}

	public List<RangerPolicy> getServicePolicies(Long serviceId) {
		List<RangerPolicy> ret = null;

		if(serviceId != null) {
			XXService xService = getXXService(serviceId);

			if(xService != null) {
				ret = getServicePolicies(xService);
			} else {
				if(LOG.isDebugEnabled()) {
					LOG.debug("RangerPolicyRetriever.getServicePolicies(serviceId=" + serviceId + "): service not found");
				}
			}
		}

		return ret;
	}

	public List<RangerPolicy> getServicePolicies(String serviceName) {
		List<RangerPolicy> ret = null;

		if(serviceName != null) {
			XXService xService = getXXService(serviceName);

			if(xService != null) {
				ret = getServicePolicies(xService);
			} else {
				if(LOG.isDebugEnabled()) {
					LOG.debug("RangerPolicyRetriever.getServicePolicies(serviceName=" + serviceName + "): service not found");
				}
			}
		}

		return ret;
	}

	private class PolicyLoaderThread extends Thread {
		final TransactionTemplate txTemplate;
		final XXService           xService;
		List<RangerPolicy>  policies;

		PolicyLoaderThread(TransactionTemplate txTemplate, final XXService xService) {
			this.txTemplate = txTemplate;
			this.xService   = xService;
		}

		public List<RangerPolicy> getPolicies() { return policies; }

		@Override
		public void run() {
			try {
				txTemplate.setReadOnly(true);
				policies = txTemplate.execute(new TransactionCallback<List<RangerPolicy>>() {
					@Override
					public List<RangerPolicy> doInTransaction(TransactionStatus status) {
						try {
							RetrieverContext ctx = new RetrieverContext(xService);
							return ctx.getAllPolicies();
						} catch (Exception ex) {
							LOG.error("RangerPolicyRetriever.getServicePolicies(): Failed to get policies for service:[" + xService.getName() + "] in a new transaction", ex);
							status.setRollbackOnly();
							return null;
						}
					}
				});
			} catch (Throwable ex) {
				LOG.error("RangerPolicyRetriever.getServicePolicies(): Failed to get policies for service:[" + xService.getName() + "] in a new transaction", ex);
			}
		}
	}

	public List<RangerPolicy> getServicePolicies(final XXService xService) {
		String serviceName = xService == null ? null : xService.getName();
		Long   serviceId   = xService == null ? null : xService.getId();

		if(LOG.isDebugEnabled()) {
			LOG.debug("==> RangerPolicyRetriever.getServicePolicies(serviceName=" + serviceName + ", serviceId=" + serviceId + ")");
		}

		List<RangerPolicy> ret  = null;
		RangerPerfTracer   perf = null;

		if(RangerPerfTracer.isPerfTraceEnabled(PERF_LOG)) {
			perf = RangerPerfTracer.getPerfTracer(PERF_LOG, "RangerPolicyRetriever.getServicePolicies(serviceName=" + serviceName + ",serviceId=" + serviceId + ")");
		}

		if(xService != null) {
			if (txTemplate == null) {
				if (LOG.isDebugEnabled()) {
					LOG.debug("Transaction Manager is null; Retrieving policies in the existing transaction");
				}
				RetrieverContext ctx = new RetrieverContext(xService);
				ret = ctx.getAllPolicies();
			} else {
				if (LOG.isDebugEnabled()) {
					LOG.debug("Retrieving policies in a new, read-only transaction");
				}

				PolicyLoaderThread t = new PolicyLoaderThread(txTemplate, xService);
				t.start();
				try {
					t.join();
					ret = t.getPolicies();
				} catch (InterruptedException ie) {
					LOG.error("Failed to retrieve policies in a new, read-only thread.", ie);
				}
			}
		} else {
			if(LOG.isDebugEnabled()) {
				LOG.debug("RangerPolicyRetriever.getServicePolicies(xService=" + xService + "): invalid parameter");
			}
		}

		RangerPerfTracer.log(perf);

		if(LOG.isDebugEnabled()) {
			LOG.debug("<== RangerPolicyRetriever.getServicePolicies(serviceName=" + serviceName + ", serviceId=" + serviceId + "): policyCount=" + (ret == null ? 0 : ret.size()));
		}

		return ret;
	}

	public RangerPolicy getPolicy(Long policyId) {
		RangerPolicy ret = null;

		if(policyId != null) {
			XXPolicy xPolicy = getXXPolicy(policyId);

			if(xPolicy != null) {
				ret = getPolicy(xPolicy);
			} else {
				if(LOG.isDebugEnabled()) {
					LOG.debug("RangerPolicyRetriever.getPolicy(policyId=" + policyId + "): policy not found");
				}
			}

		}

		return ret;
	}

	public RangerPolicy getPolicy(XXPolicy xPolicy) {
		RangerPolicy ret = null;

		if(xPolicy != null) {
			XXService xService = getXXService(xPolicy.getService());

			if(xService != null) {
				ret = getPolicy(xPolicy, xService);
			} else {
				if(LOG.isDebugEnabled()) {
					LOG.debug("RangerPolicyRetriever.getPolicy(policyId=" + xPolicy.getId() + "): service not found (serviceId=" + xPolicy.getService() + ")");
				}
			}
		}

		return ret;
	}

	public RangerPolicy getPolicy(XXPolicy xPolicy, XXService xService) {
		Long policyId = xPolicy == null ? null : xPolicy.getId();

		if(LOG.isDebugEnabled()) {
			LOG.debug("==> RangerPolicyRetriever.getPolicy(" + policyId + ")");
		}

		RangerPolicy     ret  = null;
		RangerPerfTracer perf = null;

		if(RangerPerfTracer.isPerfTraceEnabled(PERF_LOG)) {
			perf = RangerPerfTracer.getPerfTracer(PERF_LOG, "RangerPolicyRetriever.getPolicy(policyId=" + policyId + ")");
		}

		if(xPolicy != null && xService != null) {
			RetrieverContext ctx = new RetrieverContext(xPolicy, xService);

			ret = ctx.getNextPolicy();
		} else {
			if(LOG.isDebugEnabled()) {
				LOG.debug("RangerPolicyRetriever.getPolicy(xPolicy=" + xPolicy + ", xService=" + xService + "): invalid parameter(s)");
			}
		}

		RangerPerfTracer.log(perf);

		if(LOG.isDebugEnabled()) {
			LOG.debug("<== RangerPolicyRetriever.getPolicy(" + policyId + "): " + ret);
		}

		return ret;
	}

	private XXService getXXService(Long serviceId) {
		XXService ret = null;

		if(serviceId != null) {
			ret = daoMgr.getXXService().getById(serviceId);
		}

		return ret;
	}

	private XXService getXXService(String serviceName) {
		XXService ret = null;

		if(serviceName != null) {
			ret = daoMgr.getXXService().findByName(serviceName);
		}

		return ret;
	}

	private XXPolicy getXXPolicy(Long policyId) {
		XXPolicy ret = null;

		if(policyId != null) {
			ret = daoMgr.getXXPolicy().getById(policyId);
		}

		return ret;
	}

	class LookupCache {
		final Map<Long, String>              userScreenNames            = new HashMap<Long, String>();
		final Map<Long, Map<String, String>> groupMappingsPerPolicy     = new HashMap<>();
		final Map<Long, Map<String, String>> userMappingsPerPolicy      = new HashMap<>();
		final Map<Long, Map<String, String>> accessMappingsPerPolicy    = new HashMap<>();
		final Map<Long, Map<String, String>> resourceMappingsPerPolicy  = new HashMap<>();
		final Map<Long, Map<String, String>> dataMaskMappingsPerPolicy  = new HashMap<>();
		final Map<Long, Map<String, String>> conditionMappingsPerPolicy = new HashMap<>();

		String getUserScreenName(Long userId) {
			String ret = null;

			if(userId != null) {
				ret = userScreenNames.get(userId);

				if(ret == null) {
					XXPortalUser user = daoMgr.getXXPortalUser().getById(userId);

					if(user != null) {
						ret = user.getPublicScreenName();

						if (StringUtil.isEmpty(ret)) {
							ret = user.getFirstName();

							if(StringUtil.isEmpty(ret)) {
								ret = user.getLoginId();
							} else {
								if(!StringUtil.isEmpty(user.getLastName())) {
									ret += (" " + user.getLastName());
								}
							}
						}

						if(ret != null) {
							userScreenNames.put(userId, ret);
						}
					}
				}
			}

			return ret;
		}

		void setNameMapping(Map<Long, Map<String, String>> nameMappingContainer, List<PolicyTextNameMap> nameMappings) {
			nameMappingContainer.clear();

			for (PolicyTextNameMap nameMapping : nameMappings) {
				Map<String, String> policyNameMap = nameMappingContainer.get(nameMapping.policyId);

				if (policyNameMap == null) {
					policyNameMap = new HashMap<>();

					nameMappingContainer.put(nameMapping.policyId, policyNameMap);
				}

				policyNameMap.put(nameMapping.oldName, nameMapping.currentName);
			}
		}

		String getMappedName(Map<Long, Map<String, String>> nameMappingContainer, Long policyId, String nameToMap) {
			Map<String, String> policyNameMap = nameMappingContainer.get(policyId);

			return policyNameMap != null ? policyNameMap.get(nameToMap) : null;
		}

		void setGroupNameMapping(List<PolicyTextNameMap> groupNameMapping) {
			setNameMapping(groupMappingsPerPolicy, groupNameMapping);
		}

		void setUserNameMapping(List<PolicyTextNameMap> userNameMapping) {
			setNameMapping(userMappingsPerPolicy, userNameMapping);
		}

		void setAccessNameMapping(List<PolicyTextNameMap> accessNameMapping) {
			setNameMapping(accessMappingsPerPolicy, accessNameMapping);
		}

		public void setResourceNameMapping(List<PolicyTextNameMap> resourceNameMapping) {
			setNameMapping(resourceMappingsPerPolicy, resourceNameMapping);
		}

		public void setDataMaskNameMapping(List<PolicyTextNameMap> dataMaskMapping) {
			setNameMapping(dataMaskMappingsPerPolicy, dataMaskMapping);
		}

		public void setConditionNameMapping(List<PolicyTextNameMap> conditionNameMapping) {
			setNameMapping(conditionMappingsPerPolicy, conditionNameMapping);
		}

	}

	public static class PolicyTextNameMap {
		final Long   policyId;
		final String oldName;
		final String currentName;

		public PolicyTextNameMap(Long policyId, String oldName, String currentName) {
			this.policyId    = policyId;
			this.oldName     = oldName;
			this.currentName = currentName;
		}
	}

    static List<XXPolicy> asList(XXPolicy policy) {
        List<XXPolicy> ret = new ArrayList<>();

        if (policy != null) {
            ret.add(policy);
        }

        return ret;
    }

	class RetrieverContext {
		final XXService              service;
		final ListIterator<XXPolicy> iterPolicy;

		RetrieverContext(XXService xService) {
			if (xService != null) {
				Long serviceId = xService.getId();

				lookupCache.setGroupNameMapping(daoMgr.getXXPolicyRefGroup().findUpdatedGroupNamesByService(serviceId));
				lookupCache.setUserNameMapping(daoMgr.getXXPolicyRefUser().findUpdatedUserNamesByService(serviceId));
				lookupCache.setAccessNameMapping(daoMgr.getXXPolicyRefAccessType().findUpdatedAccessNamesByService(serviceId));
				lookupCache.setResourceNameMapping(daoMgr.getXXPolicyRefResource().findUpdatedResourceNamesByService(serviceId));
				lookupCache.setDataMaskNameMapping(daoMgr.getXXPolicyRefDataMaskType().findUpdatedDataMaskNamesByService(serviceId));
				lookupCache.setConditionNameMapping(daoMgr.getXXPolicyRefCondition().findUpdatedConditionNamesByService(serviceId));

				this.service    = xService;
				this.iterPolicy = daoMgr.getXXPolicy().findByServiceId(serviceId).listIterator();
			} else {
				this.service    = null;
				this.iterPolicy = null;
			}
		}

		RetrieverContext(XXPolicy xPolicy, XXService xService) {
			Long policyId = xPolicy.getId();

			lookupCache.setGroupNameMapping(daoMgr.getXXPolicyRefGroup().findUpdatedGroupNamesByPolicy(policyId));
			lookupCache.setUserNameMapping(daoMgr.getXXPolicyRefUser().findUpdatedUserNamesByPolicy(policyId));
			lookupCache.setAccessNameMapping(daoMgr.getXXPolicyRefAccessType().findUpdatedAccessNamesByPolicy(policyId));
			lookupCache.setResourceNameMapping(daoMgr.getXXPolicyRefResource().findUpdatedResourceNamesByPolicy(policyId));
			lookupCache.setDataMaskNameMapping(daoMgr.getXXPolicyRefDataMaskType().findUpdatedDataMaskNamesByPolicy(policyId));
			lookupCache.setConditionNameMapping(daoMgr.getXXPolicyRefCondition().findUpdatedConditionNamesByPolicy(policyId));

			this.service    = xService;
			this.iterPolicy = asList(xPolicy).listIterator();
		}

		RangerPolicy getNextPolicy() {
			RangerPolicy ret = null;

			if (service != null && iterPolicy != null && iterPolicy.hasNext()) {
				XXPolicy xPolicy = iterPolicy.next();

				if (xPolicy != null) {
					String policyText = xPolicy.getPolicyText();

					ret = JsonUtils.jsonToObject(policyText, RangerPolicy.class);

					if (ret != null) {
						ret.setId(xPolicy.getId());
						ret.setGuid(xPolicy.getGuid());
						ret.setCreatedBy(lookupCache.getUserScreenName(xPolicy.getAddedByUserId()));
						ret.setUpdatedBy(lookupCache.getUserScreenName(xPolicy.getUpdatedByUserId()));
						ret.setCreateTime(xPolicy.getCreateTime());
						ret.setUpdateTime(xPolicy.getUpdateTime());
						ret.setVersion(xPolicy.getVersion());
						ret.setPolicyType(xPolicy.getPolicyType() == null ? RangerPolicy.POLICY_TYPE_ACCESS : xPolicy.getPolicyType());
						ret.setService(service.getName());
						updatePolicyReferenceFields(ret);
					}
				}
			}

			return ret;
		}

		void updatePolicyReferenceFields(final RangerPolicy policy) {
			final Long policyId = policy.getId();

			Map<String, String> policyResourceNameMap = lookupCache.resourceMappingsPerPolicy.get(policyId);

			if (MapUtils.isNotEmpty(policyResourceNameMap) && CollectionUtils.containsAny(policyResourceNameMap.keySet(), policy.getResources().keySet())) {
				Map<String, RangerPolicyResource> updatedResources = new HashMap<>();

				for (Map.Entry<String, RangerPolicyResource> entry : policy.getResources().entrySet()) {
					String               resourceName   = entry.getKey();
					RangerPolicyResource policyResource = entry.getValue();
					String               updatedName    = policyResourceNameMap.get(resourceName);

					if (updatedName == null) {
						updatedName = resourceName;
					}

					updatedResources.put(updatedName, policyResource);
				}

				policy.setResources(updatedResources);
			}

			for (List<? extends RangerPolicyItem> policyItems :  getAllPolicyItems(policy)) {
				if (CollectionUtils.isEmpty(policyItems)) {
					continue;
				}

				for (RangerPolicyItem policyItem : policyItems) {
					if (lookupCache.groupMappingsPerPolicy.containsKey(policyId)) {
						List<String> updatedGroups = getUpdatedNames(lookupCache.groupMappingsPerPolicy, policyId, policyItem.getGroups());

						if (updatedGroups != null) {
							policyItem.setGroups(updatedGroups);
						}
					}

					if (lookupCache.userMappingsPerPolicy.containsKey(policyId)) {
						List<String> updatedUsers = getUpdatedNames(lookupCache.userMappingsPerPolicy, policyId, policyItem.getUsers());

						if (updatedUsers != null) {
							policyItem.setUsers(updatedUsers);
						}
					}

					if (lookupCache.accessMappingsPerPolicy.containsKey(policyId)) {
						for (RangerPolicyItemAccess itemAccess : policyItem.getAccesses()) {
							String updatedName = lookupCache.getMappedName(lookupCache.accessMappingsPerPolicy, policyId, itemAccess.getType());

							if (updatedName != null) {
								itemAccess.setType(updatedName);
							}
						}
					}

					if (lookupCache.conditionMappingsPerPolicy.containsKey(policyId)) {
						for (RangerPolicyItemCondition condition : policyItem.getConditions()) {
							String updatedName = lookupCache.getMappedName(lookupCache.conditionMappingsPerPolicy, policyId, condition.getType());

							if (updatedName != null) {
								condition.setType(updatedName);
							}
						}
					}

					if (policyItem instanceof RangerDataMaskPolicyItem && lookupCache.dataMaskMappingsPerPolicy.containsKey(policyId)) {
						RangerDataMaskPolicyItem dataMaskItem = (RangerDataMaskPolicyItem) policyItem;
						String                   updatedName  = lookupCache.getMappedName(lookupCache.dataMaskMappingsPerPolicy, policyId, dataMaskItem.getDataMaskInfo().getDataMaskType());

						if (updatedName != null) {
							dataMaskItem.getDataMaskInfo().setDataMaskType(updatedName);
						}
					}
				}
			}
		}

		List<String> getUpdatedNames(final Map<Long, Map<String, String>> nameMappingContainer, final Long policyId, final List<String> namesToMap) {
			List<String>        ret           = null;
			Map<String, String> policyNameMap = nameMappingContainer.get(policyId);

			if (MapUtils.isNotEmpty(policyNameMap) && CollectionUtils.containsAny(policyNameMap.keySet(), namesToMap)) {
				ret = new ArrayList<>();

				for (String nameToMap : namesToMap) {
					String mappedName = policyNameMap.get(nameToMap);

					if (mappedName != null) {
						ret.add(mappedName);
					} else {
						ret.add(nameToMap);
					}
				}

			}

			return ret;
		}

		List<RangerPolicy> getAllPolicies() {
			List<RangerPolicy> ret = new ArrayList<>();

			if (iterPolicy != null) {
				while (iterPolicy.hasNext()) {
					RangerPolicy policy = getNextPolicy();

					if (policy != null) {
						ret.add(policy);
					}
				}
			}

			return ret;
		}
	}

	private List<List<? extends RangerPolicyItem>> getAllPolicyItems(RangerPolicy policy) {
		List<List<? extends RangerPolicyItem>> ret = new ArrayList<>();

		if (CollectionUtils.isNotEmpty(policy.getPolicyItems())) {
			ret.add(policy.getPolicyItems());
		}

		if (CollectionUtils.isNotEmpty(policy.getDenyPolicyItems())) {
			ret.add(policy.getDenyPolicyItems());
		}

		if (CollectionUtils.isNotEmpty(policy.getAllowExceptions())) {
			ret.add(policy.getAllowExceptions());
		}

		if (CollectionUtils.isNotEmpty(policy.getDenyExceptions())) {
			ret.add(policy.getDenyExceptions());
		}

		if (CollectionUtils.isNotEmpty(policy.getDataMaskPolicyItems())) {
			ret.add(policy.getDataMaskPolicyItems());
		}

		if (CollectionUtils.isNotEmpty(policy.getRowFilterPolicyItems())) {
			ret.add(policy.getRowFilterPolicyItems());
		}

		return ret;
	}
}

