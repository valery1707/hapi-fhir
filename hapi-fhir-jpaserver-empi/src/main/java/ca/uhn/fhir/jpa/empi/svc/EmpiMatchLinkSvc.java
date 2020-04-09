package ca.uhn.fhir.jpa.empi.svc;

import ca.uhn.fhir.empi.api.EmpiLinkSourceEnum;
import ca.uhn.fhir.empi.api.EmpiMatchResultEnum;
import ca.uhn.fhir.empi.api.IEmpiLinkSvc;
import ca.uhn.fhir.empi.util.EIDHelper;
import ca.uhn.fhir.empi.util.PersonHelper;
import ca.uhn.fhir.empi.util.CanonicalEID;
import ca.uhn.fhir.jpa.empi.util.EmpiUtil;
import ca.uhn.fhir.jpa.model.cross.ResourcePersistentId;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Lazy
@Service
public class EmpiMatchLinkSvc {
	@Autowired
	private EmpiResourceDaoSvc myEmpiResourceDaoSvc;
	@Autowired
	private IEmpiLinkSvc myEmpiLinkSvc;
	@Autowired
	private EmpiPersonFindingSvc myEmpiPersonFindingSvc;
	@Autowired
	private PersonHelper myPersonHelper;
	@Autowired
	private EIDHelper myEIDHelper;

	public void updateEmpiLinksForPatient(IBaseResource theResource) {
		if (EmpiUtil.isManagedByEmpi(theResource)) {
			doEmpiUpdate(theResource);
		}
	}

	private void doEmpiUpdate(IBaseResource theResource) {
		List<MatchedPersonCandidate> personCandidates = myEmpiPersonFindingSvc.findPersonCandidates(theResource);

		//0 candidates, in which case you should create a person
		if (personCandidates.isEmpty()) {
			IBaseResource newPerson = myPersonHelper.createPersonFromPatientOrPractitioner(theResource);
			myEmpiLinkSvc.updateLink(newPerson, theResource, EmpiMatchResultEnum.MATCH, EmpiLinkSourceEnum.AUTO);
		//1 candidate, in which case you should use it
		} else if (personCandidates.size() == 1) {
			MatchedPersonCandidate matchedPersonCandidate = personCandidates.get(0);
			ResourcePersistentId personPid = matchedPersonCandidate.getCandidatePersonPid();
			IBaseResource person = myEmpiResourceDaoSvc.readPersonByPid(personPid);
			if (myPersonHelper.isPotentialDuplicate(person, theResource)) {
				IBaseResource newPerson = myPersonHelper.createPersonFromPatientOrPractitioner(theResource);
				myEmpiLinkSvc.updateLink(newPerson, theResource, EmpiMatchResultEnum.MATCH, EmpiLinkSourceEnum.AUTO);
				myEmpiLinkSvc.updateLink(newPerson, person, EmpiMatchResultEnum.POSSIBLE_DUPLICATE, EmpiLinkSourceEnum.AUTO);
			} else {
				handleEidOverwrite(person, theResource);
				myEmpiLinkSvc.updateLink(person, theResource, matchedPersonCandidate.getEmpiLink().getMatchResult(), EmpiLinkSourceEnum.AUTO);
			}
		//multiple candidates, in which case they should all be tagged as POSSIBLE_MATCH. If one is already tagged as MATCH
		} else {

		}
	}

	private void handleEidOverwrite(IBaseResource thePerson, IBaseResource theResource) {
		Optional<CanonicalEID> eidFromResource = myEIDHelper.getExternalEid(theResource);
		if (eidFromResource.isPresent()) {
			myPersonHelper.updatePersonFromPatient(thePerson, theResource);
		}
	}
}
