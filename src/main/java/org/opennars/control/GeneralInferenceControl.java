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
package org.opennars.control;

import java.util.ArrayList;
import java.util.List;
import org.opennars.entity.BudgetValue;
import org.opennars.entity.Concept;
import org.opennars.entity.Item;
import org.opennars.entity.Sentence;
import org.opennars.entity.Stamp;
import org.opennars.entity.Task;
import org.opennars.inference.LocalRules;
import org.opennars.inference.RuleTables;
import org.opennars.interfaces.Timable;
import org.opennars.io.Symbols;
import org.opennars.language.CompoundTerm;
import org.opennars.language.Term;
import org.opennars.language.Variables;
import org.opennars.storage.Memory;
import org.opennars.storage.PriorityMap;

/**
 *
 * @author Patrick Hammer
 *
 */
public class GeneralInferenceControl {
    
    static DerivationContext lastNAL = null;
    public static void addTask(Memory mem, Task t, boolean derived) {
        //results go into into cyclingTasks, inputs to inputTasks
        if(derived) {
            Concept c = mem.conceptualize(t);
            mem.cyclingTasks.putIn(t);
            Concept.executeDecision(lastNAL, t);
        } else {
            mem.inputTasks.add(t);
        }
    }
    
    public static void matchQuestion(Task t, Sentence belief, DerivationContext nal) {
        if(Variables.unify(Symbols.VAR_QUERY, new Term[] {t.getTerm(), belief.getTerm()})) {
            LocalRules.trySolution(belief, t, nal, t.isInput());
        }
    }
    
    public static class FireBelief extends Item<String> {
        Memory mem; Timable time; Task task; Term taskConceptTerm; Term subterm; Concept beliefConcept; Sentence belief; boolean temporalInference;
        public FireBelief(Memory mem, Timable time, Task task, Term taskConceptTerm, Term subterm, Concept beliefConcept, Sentence belief, boolean temporalInference) {
            super(new BudgetValue(beliefConcept.getPriority() * (belief == null ? 0.5f : belief.getTruth().getExpectation()), mem.narParameters.TASKLINK_FORGET_DURATIONS, 0.0f, mem.narParameters));
            this.mem = mem; this.time = time; this.task = task; this.taskConceptTerm = taskConceptTerm; this.subterm = subterm; this.beliefConcept = beliefConcept; this.belief = belief; this.temporalInference = temporalInference;
        }
        public void execute() {
            //Create a derivation context that works with OpenNARS "deriver":
            DerivationContext nal = new DerivationContext(mem, mem.narParameters, time);
            lastNAL = nal;
            nal.setCurrentTask(task);
            nal.setCurrentTerm(taskConceptTerm);
            nal.setCurrentConcept(beliefConcept);
            nal.setCurrentBelief(belief);
            if(belief != null) {
                nal.setTheNewStamp(task.sentence.stamp, belief.stamp, time.time());
            } else {
                nal.setTheNewStamp(new Stamp(task.sentence.stamp, time.time()));
            }
            //see whether belief answers question:
            if(!task.sentence.isJudgment() && belief != null) {
                matchQuestion(task, belief, nal);
            }
            //and fire rule table with the derivation context and our premise pair
            RuleTables.reason(task, belief, subterm, nal, temporalInference);
        }

        @Override
        public String name() {
            return "FireBelief";
        }
        
        @Override
        public boolean equals(final Object obj) {
            if (obj == this) return true;
            return false;
        }
    }
    
    
    public static void fireBelief(Memory mem, Timable time, Task task, Term taskConceptTerm, Term subterm, Concept beliefConcept, Sentence belief, boolean temporalInference) {
        FireBelief premises = new FireBelief(mem,time,task,taskConceptTerm,subterm,beliefConcept,belief,temporalInference);
        //add the potential derivation that can be done
        mem.premiseQueue.putIn(premises);
    }
    
    public static void fireTask(Task task, Memory mem, Timable time, List<Concept> highestPriorityConcepts) {
        //remove intervals, as concepts do not have them
        Term taskConceptTerm = CompoundTerm.replaceIntervals(task.getTerm());
        //get task concept, and add (in case of belief) as belief to all subterm component concepts which get created if not existent:
        Concept taskConcept = mem.conceptualize(task);
        taskConcept.addToBeliefsConceptualizingComponents(task, mem.narParameters);
        forgetConcept(mem, taskConceptTerm);
        //but we don't use the concept for inference if it has fired less than NOVELTY_HORIZON ago already
        if(time.time() - taskConcept.lastFireTime < mem.narParameters.NOVELTY_HORIZON) {
            return;
        }
        taskConcept.lastFireTime = time.time();
        //fire all beliefs of all of the subterm components
        for(Term subterm : taskConcept.termLinkTemplates.keySet()) {
            Concept beliefConcept = mem.concept(subterm);
            if(beliefConcept == null) {
                continue;
            }
            forgetConcept(mem, subterm);
            for(Task beliefT : beliefConcept.beliefs) {
                Sentence belief = beliefT.sentence;
                fireBelief(mem, time, task, taskConceptTerm, belief.term, beliefConcept, belief, false);
            }
            //virtual premise:
            fireBelief(mem, time, task, taskConceptTerm, subterm, beliefConcept, null, false);
        }
        //fire highest priority concepts for temporal inference:
        if(!task.sentence.isEternal() && task.sentence.isJudgment()) {
            for(Concept temporalC : highestPriorityConcepts) {
                if(temporalC.event != null) {
                    Sentence belief = temporalC.event.sentence;
                    fireBelief(mem, time, task, taskConceptTerm, belief.term, temporalC, belief, true);
                }
            }
        }
    }

    public static void forgetConcept(Memory mem, Term taskConceptTerm) {
        //apply forgetting:
        Concept ret = mem.concepts.take(taskConceptTerm);
        mem.concepts.putBack(ret, mem.narParameters.CONCEPT_FORGET_DURATIONS, mem);
    }
    
    public static void ALANNCircle(Memory mem, Timable time) {
        //1. get the 10 highest priority concepts for temporal inference:
        List<Concept> highestPriorityConcepts = new ArrayList<>();
        for(int i=0; i<mem.narParameters.SEQUENCE_BAG_ATTEMPTS; i++) {
            Concept highest = mem.concepts.takeHighestPriorityItem();
            if(highest != null) {
                highestPriorityConcepts.add(highest);
            }
        }
        //and forget them a bit
        for(Concept c : highestPriorityConcepts) {
            mem.concepts.putBack(c, mem.narParameters.CONCEPT_FORGET_DURATIONS, mem);
        }
        
        //Select tasks
        List<Task> selected = new ArrayList<>();
        for(int i=0; i<mem.narParameters.TASKS_MAX_FIRED; i++) {
            //check for input buffer element first
            if(mem.inputTasks != null && !mem.inputTasks.isEmpty()) {
                selected.add(mem.inputTasks.removeFirst());
            } 
            //if none such exists, use one of the cycling tasks
            else if(!mem.cyclingTasks.isEmpty()){
                selected.add((Task) mem.cyclingTasks.takeHighestPriorityItem());
            }
        }        
        //fire the task and put it back into cycling tasks
        for(Task task: selected) {
            //at first activate concept and replace with the input event task if it is one:
            mem.conceptualize(task);
        }
        for(Task task : selected) {
            fireTask(task, mem, time, highestPriorityConcepts);
            mem.cyclingTasks.putBack(task, mem.narParameters.TASKLINK_FORGET_DURATIONS, mem);
        }
        //derive a batch of premises from the premises queue:
        for(int i=0; i<mem.narParameters.PREMISES_MAX_FIRED; i++) {
            FireBelief bel = (FireBelief) mem.premiseQueue.takeHighestPriorityItem();
            if(bel != null) {
                bel.execute();
            } else {
                break;
            }
        }
    }
}
