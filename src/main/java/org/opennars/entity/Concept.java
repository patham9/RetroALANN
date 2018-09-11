/* 
 * The MIT License
 *
 * Copyright 2018 The OpenNARS authors.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.opennars.entity;

import org.opennars.control.DerivationContext;
import org.opennars.inference.LocalRules;
import org.opennars.interfaces.Timable;
import org.opennars.io.Symbols.NativeOperator;
import org.opennars.language.CompoundTerm;
import org.opennars.language.Term;
import org.opennars.main.Shell;
import org.opennars.storage.Memory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.opennars.inference.BudgetFunctions.rankBelief;
import org.opennars.io.events.Events;
import org.opennars.main.Parameters;

/**
 * Concept as defined by the NARS-theory
 *
 * Concepts are used to keep track of interrelated sentences
 *
 * @author Pei Wang
 * @author Patrick Hammer
 */
public class Concept extends Item<Term> implements Serializable {

    public long lastFireTime = -1000;
    /**
     * The term is the unique ID of the concept
     */
    public final Term term;

    /**
     * Link templates of TermLink, only in concepts with CompoundTerm Templates
     * are used to improve the efficiency of TermLink building
     */
    public final Map<Term,TermLink> termLinkTemplates;

    /**
     * Judgments directly made about the term Use List because of access
     * and insertion in the middle
     */
    public final List<Task> beliefs;
    /**
     * Desire values on the term, similar to the above one
     */
    
    /**
     * Reference to the memory to which the Concept belongs
     */
    public final Memory memory;
    

    //use to create averaging stats of occurring intervals
    //so that revision can decide whether to use the new or old term
    //based on which intervals are closer to the average
    public final List<Float> recent_intervals = new ArrayList<>();

    public boolean observable = false;

    /**
     * Constructor, called in Memory.getConcept only
     *
     * @param tm A term corresponding to the concept
     * @param memory A reference to the memory
     */
    public Concept(final BudgetValue b, final Term tm, final Memory memory) {
        super(b);        
        
        this.term = tm;
        this.memory = memory;

        this.beliefs = new ArrayList<>();
        
        if (tm instanceof CompoundTerm) {
            this.termLinkTemplates = ((CompoundTerm) tm).prepareComponentLinks();
        } else {
            this.termLinkTemplates = null;
        }

    }

    @Override public boolean equals(final Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Concept)) return false;
        return ((Concept)obj).name().equals(name());
    }

    @Override public int hashCode() { return name().hashCode();     }

    
    @Override
    public Term name() {
        return term;
    }



    public void addToTable(final Task task, final boolean rankTruthExpectation, final List<Task> table, final int max, final Class eventAdd, final Class eventRemove, final Object... extraEventArguments) {
        
        final int preSize = table.size();
        final Task removedT;
        Sentence removed = null;
        removedT = addToTable(task, table, max, rankTruthExpectation);
        if(removedT != null) {
            removed=removedT.sentence;
        }

        if (removed != null) {
            memory.event.emit(eventRemove, this, removed, task, extraEventArguments);
        }
        if ((preSize != table.size()) || (removed != null)) {
            memory.event.emit(eventAdd, this, task, extraEventArguments);
        }
    }
    
    /**
     * Add to beliefs in all relevant concepts
     * <p>
     * The only method that calls the TaskLink constructor.
     *
     * @param task The task to be linked
     * @param content The content of the task
     */
    public void addToBeliefsConceptualizingComponents(final Task task, final Parameters narParameters) {
        //memory.conceptualize(task.budget, CompoundTerm.replaceIntervals(task.getTerm()));
        if(task.sentence.isJudgment()) {
            this.addToTable(task, true, beliefs, narParameters.CONCEPT_BELIEFS_MAX, Events.ConceptBeliefAdd.class, Events.ConceptBeliefRemove.class);
        }
        if (!(term instanceof CompoundTerm)) {
            return;
        }
        if (termLinkTemplates.isEmpty()) {
            return;
        }

        for (final Term componentTerm : termLinkTemplates.keySet()) {
            final Concept componentConcept = memory.conceptualize(task.budget, componentTerm);

            if (componentConcept != null && task.sentence.isJudgment()) {
                synchronized(componentConcept) {
                    componentConcept.addToTable(task, true, componentConcept.beliefs, narParameters.CONCEPT_BELIEFS_MAX, Events.ConceptBeliefAdd.class, Events.ConceptBeliefRemove.class);
                }
            }
        };
    }

    /**
     * Add a new belief (or goal) into the table Sort the beliefs/desires by
     * rank, and remove redundant or low rank one
     *
     * @param table The table to be revised
     * @param capacity The capacity of the table
     * @return whether table was modified
     */
    public static Task addToTable(final Task newTask, final List<Task> table, final int capacity, final boolean rankTruthExpectation) {
        final Sentence newSentence = newTask.sentence;
        final float rank1 = rankBelief(newSentence, rankTruthExpectation);    // for the new isBelief
        float rank2;        
        int i;
        for (i = 0; i < table.size(); i++) {
            final Sentence judgment2 = table.get(i).sentence;
            rank2 = rankBelief(judgment2, rankTruthExpectation);
            if (rank1 >= rank2) {
                if (newSentence.truth.equals(judgment2.truth) && newSentence.stamp.equals(judgment2.stamp,false,true,true)) {
                    //System.out.println(" ---------- Equivalent Belief: " + newSentence + " == " + judgment2);
                    return null;
                }
                table.add(i, newTask);
                break;
            }            
        }
        
        if (table.size() == capacity) {
            // nothing
        }
        else if (table.size() > capacity) {
            final Task removed = table.remove(table.size() - 1);
            return removed;
        }
        else if (i == table.size()) { // branch implies implicit table.size() < capacity
            table.add(newTask);
        }
        
        return null;
    }

    /**
     * Select a belief value or desire value for a given query
     *
     * @param query The query to be processed
     * @param list The list of beliefs or desires to be used
     * @return The best candidate selected
     */
    public Task selectCandidate(final Task query, final List<Task> list, final Timable time) {
 //        if (list == null) {
        //            return null;
        //        }
        float currentBest = 0;
        float beliefQuality;
        Task candidate = null;
        final boolean rateByConfidence = true; //table vote, yes/no question / local processing
        synchronized (list) {
            for (final Task judgT : list) {
                final Sentence judg = judgT.sentence;
                beliefQuality = LocalRules.solutionQuality(rateByConfidence, query, judg, memory, time); //makes revision explicitly search for
                if (beliefQuality > currentBest /*&& (!forRevision || judgT.sentence.equalsContent(query)) */ /*&& (!forRevision || !Stamp.baseOverlap(query.stamp.evidentialBase, judg.stamp.evidentialBase)) */) {
                    currentBest = beliefQuality;
                    candidate = judgT;
                }
            }
        }
        return candidate;
    }

    /**
     * Return a string representation of the concept, called in ConceptBag only
     *
     * @return The concept name, with taskBudget in the full version
     */
    @Override
    public String toString() {  // called from concept bag
        //return (super.toStringBrief() + " " + key);
        return super.toStringExternal();
    }

    /**
     * called from {@link Shell}
     */
    @Override
    public String toStringLong() {
        final String res =
                toStringExternal() + " " + term.name()
                + toStringIfNotNull(beliefs.size(), "beliefs");
        
                //+ toStringIfNotNull(null, "questions");
        /*for (Task t : questions) {
            res += t.toString();
        }*/
        // TODO other details?
        return res;
    }

    private String toStringIfNotNull(final Object item, final String title) {
        if (item == null) {
            return "";
        }

        final String itemString = item.toString();

        return new StringBuilder(2 + title.length() + itemString.length() + 1).
                append(" ").append(title).append(':').append(itemString).toString();
    }

    /**
     * Return the templates for TermLinks, only called in
     * Memory.continuedProcess
     *
     * @return The template get
     */
    public Map<Term,TermLink> getTermLinkTemplates() {
        return termLinkTemplates;
    }

    @Override
    public void end() {        
        beliefs.clear();
        termLinkTemplates.clear();
    }

    public void discountConfidence(final boolean onBeliefs) {
        if (onBeliefs) {
            for (final Task t : beliefs) {
                t.sentence.discountConfidence(memory.narParameters);
            }
        }
    }

    public NativeOperator operator() {
        return term.operator();
    }

    public Term getTerm() {
        return term;
    }

    /** returns unmodifidable collection wrapping beliefs */
    public List<Task> getBeliefs() {
        return Collections.unmodifiableList(beliefs);
    }
}
