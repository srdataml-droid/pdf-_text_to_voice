package dev.anchor.app;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class PlanDraftingTest {
    @Test public void failedInferenceStillOpensAnEditableCompleteList() {
        String source="Sort these tasks:\nRead about health\nWash dishes\nHackathon work";
        PlanDrafting.Draft draft=PlanDrafting.create(source,true,input->{throw new IllegalStateException("Unsupported model");},()->false);
        assertEquals(3,draft.items.size());assertEquals(source,draft.source);
        assertEquals("Hackathon work",draft.items.get(2).title);
        assertTrue(draft.explanation.contains("could not"));
    }
    @Test public void longListsNeverSendATruncatedPrefixToTheModel() {
        StringBuilder input=new StringBuilder("Sort these tasks:\n");
        for(int i=0;i<12;i++)input.append("Activity ").append(i).append(" ").append(String.join("",Collections.nCopies(95,"a"))).append('\n');
        AtomicBoolean invoked=new AtomicBoolean();
        PlanDrafting.Draft draft=PlanDrafting.create(input.toString(),true,text->{invoked.set(true);return Collections.emptyList();},()->false);
        assertFalse(invoked.get());assertEquals(12,draft.items.size());
        assertTrue(draft.items.get(11).title.startsWith("Activity 11 "));assertEquals(input.toString(),draft.source);
    }
    @Test public void cancellationDoesNotOpenFallbackOrModelDraft() {
        AtomicBoolean stopped=new AtomicBoolean();
        assertNull(PlanDrafting.create("Tasks:\nRest\nRead",true,text->{stopped.set(true);return PlanText.starter();},stopped::get));
    }
    @Test public void invalidFallbackPreservesTheSourceForManualEditing() {
        StringBuilder input=new StringBuilder("Tasks:\n");for(int i=0;i<13;i++)input.append("Task ").append(i).append('\n');
        PlanDrafting.Draft draft=PlanDrafting.create(input.toString(),false,text->{fail();return null;},()->false);
        assertTrue(draft.items.isEmpty());assertEquals(input.toString(),draft.source);assertTrue(draft.explanation.contains("12"));
    }
}
