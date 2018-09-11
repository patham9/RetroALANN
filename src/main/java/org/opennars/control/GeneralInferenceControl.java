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
import org.opennars.entity.Concept;
import org.opennars.entity.Sentence;
import org.opennars.entity.Task;
import org.opennars.inference.RuleTables;
import org.opennars.interfaces.Timable;
import org.opennars.language.CompoundTerm;
import org.opennars.language.Term;
import org.opennars.storage.Memory;

/**
 *
 * @author Patrick Hammer
 *
 */
public class GeneralInferenceControl {
    
    public static void addTask(Memory mem, Task t, boolean derived) {
        //results go into into cyclingTasks, inputs to inputTasks
        if(derived) {
            mem.cyclingTasks.putIn(t);
        } else {
            mem.inputTasks.add(t);
        }
    }
    
    public static void fireBelief(Memory mem, Timable time, Task task, Term taskConceptTerm, Term subterm, Concept taskConcept, Sentence belief) {
        //Create a derivation context that works with OpenNARS "deriver":
        DerivationContext cont = new DerivationContext(mem, mem.narParameters, time);
        cont.setCurrentTask(task);
        cont.setCurrentTerm(taskConceptTerm);
        cont.setCurrentConcept(taskConcept);
        cont.setCurrentBelief(belief);
        cont.setTheNewStamp(task.sentence.stamp, belief.stamp, time.time());
        //and fire rule table with the derivation context and our premise pair
        RuleTables.reason(task, belief, subterm, cont);
    }
    
    public static void fireTask(Task task, Memory mem, Timable time) {
        //remove intervals, as concepts do not have them
        Term taskConceptTerm = CompoundTerm.replaceIntervals(task.getTerm());
        //conceptualize task concept, and add (in case of belief) as belief to all subterm component concepts:
        Concept taskConcept = mem.conceptualize(task.budget, taskConceptTerm);
        taskConcept.addToBeliefsConceptualizingComponents(task, mem.narParameters);
        //but we don't use the concept for inference if it has fired less than NOVELTY_HORIZON ago already
        if(time.time() - taskConcept.lastFireTime < mem.narParameters.NOVELTY_HORIZON) {
            return;
        }
        taskConcept.lastFireTime = time.time();
        //fire all beliefs of all of the subterm components
        for(Term subterm : taskConcept.termLinkTemplates.keySet()) {
            Concept beliefConcept = mem.concept(subterm);
            for(Task beliefT : beliefConcept.beliefs) {
                Sentence belief = beliefT.sentence;
                fireBelief(mem, time, task, taskConceptTerm, subterm, taskConcept, belief);
            }
        }
    }
    
    public static void ALANNCircle(Memory mem, Timable time) {
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
        for(Task task : selected) {
            fireTask(task, mem, time);
            mem.cyclingTasks.putBack(task, mem.narParameters.TASKLINK_FORGET_DURATIONS, mem);
        }
    }
}
