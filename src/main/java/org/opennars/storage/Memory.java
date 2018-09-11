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
package org.opennars.storage;

import org.opennars.control.GeneralInferenceControl;
import org.opennars.entity.*;
import org.opennars.inference.BudgetFunctions;
import org.opennars.interfaces.Resettable;
import org.opennars.interfaces.Timable;
import org.opennars.io.Symbols;
import org.opennars.io.events.EventEmitter;
import org.opennars.io.events.Events;
import org.opennars.io.events.Events.ResetEnd;
import org.opennars.io.events.Events.ResetStart;
import org.opennars.io.events.Events.TaskRemove;
import org.opennars.io.events.OutputHandler.IN;
import org.opennars.io.events.OutputHandler.OUT;
import org.opennars.language.CompoundTerm;
import org.opennars.language.Interval;
import org.opennars.language.Tense;
import org.opennars.language.Term;
import org.opennars.main.Nar;
import org.opennars.main.Parameters;
import org.opennars.operator.Operation;
import org.opennars.operator.Operator;

import java.io.Serializable;
import java.util.*;
import org.opennars.entity.Stamp.BaseEntry;

import static org.opennars.inference.BudgetFunctions.truthToQuality;


/**
 * Memory consists of the run-time state of a Nar, including:
 *   * term and concept memory
 *   * reasoner state
 *   * etc.
 * <br>
 * Excluding input/output channels which are managed by a Nar.
 * <br>
 * A memory is controlled by zero or one Nar's at a given time.
 * <br>
 * Memory is serializable so it can be persisted and transported.
 */
public class Memory implements Serializable, Iterable<Concept>, Resettable {
    
     /* Nar parameters */
    public final Parameters narParameters;
    
    public long narId = 0;
    public static final long randomSeed = 1;
    public static final Random randomNumber = new Random(randomSeed);
    public transient EventEmitter event;
    
    /* InnateOperator registry. Containing all registered operators of the system */
    public final Map<CharSequence, Operator> operators;
    
    public final PriorityMap<Term,Concept> concepts;

    /* List of new tasks accumulated in one cycle, to be processed in the next cycle */
    public final Deque<Task> inputTasks;
    public final PriorityMap cyclingTasks;
    
    //Boolean localInferenceMutex = false;
    
    public static void resetStatic() {
        randomNumber.setSeed(randomSeed);    
    }
    
    /* ---------- Constructor ---------- */
    /**
     * Create a new memory
     */
    public Memory(final Parameters narParameters, final PriorityMap<Term, Concept> concepts) {
        this.narParameters = narParameters;
        this.event = new EventEmitter();
        this.concepts = concepts;             
        this.inputTasks = new ArrayDeque<>();
        this.cyclingTasks = new PriorityMap(narParameters.TASK_LINK_BAG_SIZE);
        this.operators = new HashMap<>();
        reset();
    }
    
    public void reset() {
        event.emit(ResetStart.class);
        resetStatic();
        event.emit(ResetEnd.class);
    }

    /* ---------- conversion utilities ---------- */
    /**
     * Get an existing Concept for a given name
     * <p>
     * called from Term and ConceptWindow.
     *
     * @param t the name of a concept
     * @return a Concept or null
     */
    public Concept concept(final Term t) {
        synchronized (concepts) {
            return concepts.get(CompoundTerm.replaceIntervals(t));
        }
    }

    /**
     * Get the Concept associated to a Term, or create it.
     * 
     *   Existing concept: apply tasklink activation (remove from bag, adjust budget, reinsert)
     *   New concept: set initial activation, insert
     *   Subconcept: extract from cache, apply activation, insert
     * 
     * If failed to insert as a result of null bag, returns null
     *
     * A displaced Concept resulting from insert is forgotten (but may be stored in optional  subconcept memory
     * 
     * @param term indicating the concept
     * @return an existing Concept, or a new one, or null 
     */
    public Concept conceptualize(final BudgetValue budget, Term term) {   
        if(term instanceof Interval) {
            return null;
        }
        term = CompoundTerm.replaceIntervals(term);

        final Concept displaced;
        Concept concept;

        synchronized (concepts) {
            concept = concepts.take(term);

            //see if concept is active
            if (concept == null) {
                //create new concept, with the applied budget
                concept = new Concept(budget, term, this);
                //if (memory.logic!=null)
                //    memory.logic.CONCEPT_NEW.commit(term.getComplexity());
                emit(Events.ConceptNew.class, concept);
            }
            else if (concept!=null) {
                //apply budget to existing concept
                //memory.logic.CONCEPT_ACTIVATE.commit(term.getComplexity());
                BudgetFunctions.activate(concept.budget, budget);
            }
            else {
                //unable to create, ex: has variables
                return null;
            }

            displaced = concepts.putBack(concept, cycles(narParameters.CONCEPT_FORGET_DURATIONS), this);
        }

        if (displaced == null) {
            //added without replacing anything
            return concept;
        }        
        else if (displaced == concept) {
            //not able to insert
            conceptRemoved(displaced);
            return null;
        }        
        else {
            conceptRemoved(displaced);
            return concept;
        }
    }
    
    /* ---------- new task entries ---------- */
    /**
     * add new task that waits to be processed in the next cycleMemory
     */
    public void addNewTask(final Task t, final String reason) {
        GeneralInferenceControl.addTask(this, t, reason.startsWith("Derived"));
        emit(Events.TaskAdd.class, t, reason);
        output(t);
    }
    
    boolean checked=false;
    boolean isjUnit=false;
    public static boolean isJUnitTest() {
        final StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        final StackTraceElement[] list = stackTrace;
        for (final StackTraceElement element : list) {
            if (element.getClassName().startsWith("org.junit.")) {
                return true;
            }           
        }
        return false;
    }
    
    /* There are several types of new tasks, all added into the
     newTasks list, to be processed in the next cycleMemory.
     Some of them are reported and/or logged. */
    /**
     * Input task processing. Invoked by the outside or inside environment.
 Outside: StringParser (addInput); Inside: InnateOperator (feedback). Input
 tasks with low priority are ignored, and the others are put into task
 buffer.
     *
     * @param time indirection to retrieve time
     * @param t The addInput task
     */
    public void inputTask(final Timable time, final Task t, final boolean emitIn) {
        if(!checked) {
            checked=true;
            isjUnit=isJUnitTest();
        }
        if (t instanceof Task) {
            final Task task = t;
            final Stamp s = task.sentence.stamp;
            if (s.getCreationTime()==-1)
                s.setCreationTime(time.time(), narParameters.DURATION);

            if(emitIn) {
                emit(IN.class, task);
            }

            if (task.budget.aboveThreshold()) {
                addNewTask(task, "Perceived");
            } else {
                removeTask(task, "Neglected");
            }
        }
    }

    /**
     * @param time indirection to retrieve time
     */
    public void inputTask(final Timable time, final Task t) {
        inputTask(time, t, true);
    }

    public void removeTask(final Task task, final String reason) {        
        emit(TaskRemove.class, task, reason);
        task.end();        
    }
    
    /**
     * ExecutedTask called in Operator.call
     *
     * @param operation The operation just executed
     * @param time indirection to retrieve time
     */
    public void executedTask(final Timable time, final Operation operation, final TruthValue truth) {                
        final Stamp stamp = new Stamp(time, this, Tense.Present);
        final Sentence sentence = new Sentence(
            operation,
            Symbols.JUDGMENT_MARK,
            truth,
            stamp);

        final BudgetValue budgetForNewTask = new BudgetValue(narParameters.DEFAULT_FEEDBACK_PRIORITY,
            narParameters.DEFAULT_FEEDBACK_DURABILITY,
            truthToQuality(sentence.getTruth()), narParameters);
        final Task newTask = new Task(sentence, budgetForNewTask, Task.EnumType.INPUT);

        newTask.setElemOfSequenceBuffer(true);
        addNewTask(newTask, "Executed");
    }

    public void output(final Task t) {
        
        final float budget = t.budget.summary();
        final float noiseLevel = 1.0f - (narParameters.VOLUME / 100.0f);
        
        if (budget >= noiseLevel) {  // only report significant derived Tasks
            emit(OUT.class, t);
        }        
    }
    
    final public void emit(final Class c, final Object... signal) {        
        event.emit(c, signal);
    }

    final public boolean emitting(final Class channel) {
        return event.isActive(channel);
    }
    
    public void conceptRemoved(final Concept c) {
        emit(Events.ConceptForget.class, c);
    }
    
    public void cycle(final Nar inputs) {
    
        event.emit(Events.CycleStart.class);
        
    //if(noResult()) //newTasks empty
        GeneralInferenceControl.ALANNCircle(this, inputs);
        
        event.emit(Events.CycleEnd.class);
        event.synch();
    }

     public Operator getOperator(final String op) {
        return operators.get(op);
     }
     
     public Operator addOperator(final Operator op) {
         operators.put(op.name(), op);
         return op;
     }
     
     public Operator removeOperator(final Operator op) {
         return operators.remove(op.name());
     }

    private long currentStampSerial = 0;
    public BaseEntry newStampSerial() {
        return new BaseEntry(this.narId, currentStampSerial++);
    }   

    /** converts durations to cycles */
    public final float cycles(final double durations) {
        return narParameters.DURATION * (float) durations;
    }

    @Override
    public Iterator<Concept> iterator() {
        return concepts.iterator();
    }
}
