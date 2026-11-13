package dev.anchor.app;
import android.test.AndroidTestCase;
public class PlanProposalDeviceTest extends AndroidTestCase {
    private final String valid="{\"action\":\"propose_plan\",\"items\":[[\"Reading\",30,127,\"challenge\"],[\"Rest\",45,127,\"daily\"]]}";
    public void testCompactProposalProducesOnlyEditableDefinitions(){assertEquals(2,PlanProposal.parse(valid).size());assertEquals("challenge",PlanProposal.parse(valid).get(0).category);}
    public void testRejectUnknownActionsKeysTypesAndTruncatedOutput(){
        for(String s:new String[]{valid.replace("propose_plan","delete_all"),valid.replace("\"items\"","\"execute\":true,\"items\""),valid.replace("30,127","\"30\",127"),valid.replace("30,127","30,0"),valid.replace("challenge","admin"),valid.substring(0,valid.length()-1),valid+" {}"})assertNull(s,PlanProposal.parse(s));
    }
    public void testRejectDuplicatesAndEmptyPlans(){assertNull(PlanProposal.parse(valid.replace("Rest","Reading")));assertNull(PlanProposal.parse("{\"action\":\"propose_plan\",\"items\":[]}"));}
}
