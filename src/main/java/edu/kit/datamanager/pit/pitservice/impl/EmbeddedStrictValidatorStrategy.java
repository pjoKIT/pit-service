package edu.kit.datamanager.pit.pitservice.impl;

import edu.kit.datamanager.pit.common.ExternalServiceException;
import edu.kit.datamanager.pit.common.RecordValidationException;
import edu.kit.datamanager.pit.configuration.ApplicationProperties;
import edu.kit.datamanager.pit.domain.PIDRecord;
import edu.kit.datamanager.pit.domain.TypeDefinition;
import edu.kit.datamanager.pit.pitservice.IValidationStrategy;
import edu.kit.datamanager.pit.util.TypeValidationUtils;

import java.util.concurrent.ExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.google.common.cache.LoadingCache;

public class EmbeddedStrictValidatorStrategy implements IValidationStrategy {

    private static final Logger LOG = LoggerFactory.getLogger(EmbeddedStrictValidatorStrategy.class);

    @Autowired
    public LoadingCache<String, TypeDefinition> typeLoader;

    @Autowired
    ApplicationProperties applicationProps;

    @Override
    public void validate(PIDRecord pidRecord) throws RecordValidationException, ExternalServiceException {
        String profileKey = applicationProps.getProfileKey();
        if (!pidRecord.hasProperty(profileKey)) {
            throw new RecordValidationException(
                    pidRecord.getPid(),
                    "Profile attribute not found. Expected key: " + profileKey);
        }

        String[] profilePIDs = pidRecord.getPropertyValues(profileKey);
        boolean hasProfile = profilePIDs.length > 0;
        if (!hasProfile) {
            throw new RecordValidationException(
                    pidRecord.getPid(),
                    "Profile attribute " + profileKey + " has no values.");
        }

        for (String profilePID : profilePIDs) {
            TypeDefinition profileDefinition;
            try {
                profileDefinition = this.typeLoader.get(profilePID);
            } catch (ExecutionException e) {
                LOG.error("Could not resolve identifier {}.", profilePID);
                throw new ExternalServiceException(
                        applicationProps.getTypeRegistryUri().toString());
            }
            if (profileDefinition == null) {
                LOG.error("No type definition found for identifier {}.", profilePID);
                throw new RecordValidationException(
                        pidRecord.getPid(),
                        String.format("No type found for identifier %s.", profilePID));
            }

            LOG.debug("validating profile {}", profilePID);
            this.strictProfileValidation(pidRecord, profileDefinition);
            LOG.debug("successfully validated {}", profilePID);
        }
    }

    /**
     * Exceptions indicate failure. No Exceptions mean success.
     * 
     * @param pidRecord
     * @param profile
     * @throws RecordValidationException
     */
    private void strictProfileValidation(PIDRecord pidRecord, TypeDefinition profile) throws RecordValidationException {
        // if (profile.hasSchema()) {
        // TODO issue https://github.com/kit-data-manager/pit-service/issues/104
        // validate using schema and you are done (strict validation)
        // String jsonRecord = ""; // TODO format depends on schema source
        // return profile.validate(jsonRecord);
        // }

        LOG.trace("Validating PID record against type definition.");

        TypeValidationUtils.checkMandatoryAttributes(pidRecord, profile);

        for (String attributeKey : pidRecord.getPropertyIdentifiers()) {
            LOG.trace("Checking PID record key {}.", attributeKey);

            TypeDefinition type = profile.getSubTypes().get(attributeKey);
            if (type == null) {
                LOG.error("No sub-type found for key {}.", attributeKey);
                // TODO try to resolve it (for later when we support "allow additional
                // attributes")
                // if profile.allowsAdditionalAttributes() {...} else
                throw new RecordValidationException(
                        pidRecord.getPid(),
                        String.format("Attribute %s is not allowed in profile %s",
                                attributeKey,
                                profile.getIdentifier()));
            }

            validateValuesForKey(pidRecord, attributeKey, type);
        }
    }

    private void validateValuesForKey(PIDRecord pidRecord, String attributeKey, TypeDefinition type)
            throws RecordValidationException {
        String[] values = pidRecord.getPropertyValues(attributeKey);
        for (String value : values) {
            if (value == null) {
                LOG.error("'null' record value found for key {}.", attributeKey);
                throw new RecordValidationException(
                        pidRecord.getPid(),
                        String.format("Validation of value %s against type %s failed.",
                                value,
                                type.getIdentifier()));
            }

            if (!type.validate(value)) {
                LOG.error("Validation of value {} against type {} failed.", value, type.getIdentifier());
                throw new RecordValidationException(
                        pidRecord.getPid(),
                        String.format("Validation of value %s against type %s failed.",
                                value,
                                type.getIdentifier()));
            }
        }
    }
}
