/*
 * Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
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
package org.wso2.carbon.identity.sso.saml.processors;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.identity.base.IdentityException;
import org.wso2.carbon.identity.core.model.SAMLSSOServiceProviderDO;
import org.wso2.carbon.identity.sso.saml.SAMLSSOConstants;
import org.wso2.carbon.identity.sso.saml.dto.QueryParamDTO;
import org.wso2.carbon.identity.sso.saml.dto.SAMLSSOReqValidationResponseDTO;
import org.wso2.carbon.identity.sso.saml.session.SSOSessionPersistenceManager;
import org.wso2.carbon.identity.sso.saml.session.SessionInfoData;
import org.wso2.carbon.identity.sso.saml.util.SAMLSSOUtil;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;

import java.util.Map;

import static org.wso2.carbon.identity.sso.saml.util.SAMLSSOUtil.splitAppendedTenantDomain;

public class IdPInitLogoutRequestProcessor implements IdpInitSSOLogoutRequestProcessor{

    private static final Log log = LogFactory.getLog(IdPInitLogoutRequestProcessor.class);

    private String spEntityID;
    private String returnTo;

    /**
     * @deprecated This method was deprecated to move SAMLSSOParticipantCache to the tenant space.
     * Use {@link #process(String, QueryParamDTO[], String, String)} )} instead.
     */
    @Deprecated
    public SAMLSSOReqValidationResponseDTO process(String sessionId, QueryParamDTO[] queryParamDTOs,
                                                   String serverURL) throws IdentityException {

        // For backward compatibility, SUPER_TENANT_DOMAIN was used as the cache maintaining tenant.
        return process(sessionId, queryParamDTOs, serverURL, MultitenantConstants.SUPER_TENANT_DOMAIN_NAME);

    }

    /**
     * Process IDP initiated Logout Request.
     *
     * @param sessionId             Session Id.
     * @param queryParamDTOs        Query Param DTOs.
     * @param serverURL             Server url.
     * @param loginTenantDomain     Login tenant Domain.
     * @return  validationResponseDTO.
     * @throws IdentityException
     */
    public SAMLSSOReqValidationResponseDTO process(String sessionId, QueryParamDTO[] queryParamDTOs, String serverURL,
                                                   String loginTenantDomain) throws IdentityException {

        init(queryParamDTOs);

        SAMLSSOReqValidationResponseDTO validationResponseDTO = new SAMLSSOReqValidationResponseDTO();

        try {

            validationResponseDTO.setLogOutReq(true);

            if (StringUtils.isBlank(sessionId)) {
                log.error(SAMLSSOConstants.Notification.INVALID_SESSION);
                validationResponseDTO.setValid(false);
                validationResponseDTO.setLogoutFromAuthFramework(true);
                return validationResponseDTO;
            }

            SSOSessionPersistenceManager ssoSessionPersistenceManager = SSOSessionPersistenceManager
                    .getPersistenceManager();
            String sessionIndex = ssoSessionPersistenceManager.getSessionIndexFromTokenId(sessionId, loginTenantDomain);
            SessionInfoData sessionInfoData = ssoSessionPersistenceManager.
                    getSessionInfo(sessionIndex, loginTenantDomain);

            if (sessionInfoData == null) {
                log.error(SAMLSSOConstants.Notification.INVALID_SESSION);
                validationResponseDTO.setValid(false);
                validationResponseDTO.setLogoutFromAuthFramework(true);
                return validationResponseDTO;
            }
            validationResponseDTO.setSessionIndex(sessionIndex);

            Map<String, SAMLSSOServiceProviderDO> sessionsList = sessionInfoData.getServiceProviderList();

            if (StringUtils.isBlank(spEntityID)) {
                if (StringUtils.isNotBlank(returnTo)) {
                    log.error(SAMLSSOConstants.Notification.NO_SP_ENTITY_PARAM);
                    validationResponseDTO.setValid(false);
                    return validationResponseDTO;
                }

                validationResponseDTO.setReturnToURL(serverURL);
            } else {

                SAMLSSOServiceProviderDO logoutReqIssuer = sessionsList.get(spEntityID);

                if (logoutReqIssuer == null) {
                    log.error(String.format(SAMLSSOConstants.Notification.INVALID_SP_ENTITY_ID, spEntityID));
                    validationResponseDTO.setValid(false);
                    return validationResponseDTO;
                }

                if (!logoutReqIssuer.isIdPInitSLOEnabled()) {
                    log.error(String.format(SAMLSSOConstants.Notification.IDP_SLO_NOT_ENABLED, spEntityID));
                    validationResponseDTO.setValid(false);
                    return validationResponseDTO;
                }

                if (StringUtils.isNotBlank(returnTo)) {
                    if (!logoutReqIssuer.getIdpInitSLOReturnToURLList().contains(returnTo) && !logoutReqIssuer
                            .getAssertionConsumerUrlList().contains(returnTo)) {
                        log.error(SAMLSSOConstants.Notification.INVALID_RETURN_TO_URL);
                        validationResponseDTO.setValid(false);
                        return validationResponseDTO;
                    }
                    validationResponseDTO.setReturnToURL(returnTo);
                } else {
                    validationResponseDTO.setReturnToURL(serverURL + "?spEntityID=" + spEntityID);
                }
                validationResponseDTO.setIssuer(logoutReqIssuer.getIssuer());
                SAMLSSOUtil.setTenantDomainInThreadLocal(logoutReqIssuer.getTenantDomain());
            }
            validationResponseDTO.setValid(true);

        } catch (UserStoreException | IdentityException e) {
            throw IdentityException.error(SAMLSSOConstants.Notification.IDP_SLO_VALIDATE_ERROR, e);
        }
        return validationResponseDTO;
    }

    private void init(QueryParamDTO[] queryParamDTOs) {

        for (QueryParamDTO queryParamDTO : queryParamDTOs) {
            if (SAMLSSOConstants.QueryParameter.SP_ENTITY_ID.toString().equals(queryParamDTO.getKey())) {
                String issuer = splitAppendedTenantDomain(queryParamDTO.getValue());
                this.spEntityID = SAMLSSOUtil.resolveIssuerQualifier(queryParamDTOs, issuer);
            } else if (SAMLSSOConstants.QueryParameter.RETURN_TO.toString().equals(queryParamDTO.getKey())) {
                this.returnTo = queryParamDTO.getValue();
            }
        }
    }

}
