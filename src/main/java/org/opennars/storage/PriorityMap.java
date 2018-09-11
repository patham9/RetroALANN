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

import com.google.common.collect.MinMaxPriorityQueue;
import org.opennars.entity.Item;

import java.io.Serializable;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.opennars.inference.BudgetFunctions;

public class PriorityMap<K,V extends Item<K>> implements Serializable {
    public final MinMaxPriorityQueue<V> queue;
    public final Map<K,V> theMap; 
    final int maxSize;
    public PriorityMap(int maxSize) {
        this.maxSize = maxSize;
        theMap = new HashMap<>();
        queue = MinMaxPriorityQueue
                .orderedBy(Comparator.comparing(V::getPriority))
                .create();
    }
    
    public V putIn(V item) {
        V displaced = null;
        if(queue.size() >= maxSize) {
            V itemRemove = queue.removeFirst();
            theMap.remove(itemRemove.name());
            displaced = itemRemove;
        }
        queue.add(item);
        theMap.put(item.name(), item);
        return displaced;
    }
    
    public V get(K key) {
        return theMap.getOrDefault(key, null);
    }
    
    public V take(K key) {
        if(theMap.containsKey(key)) {
            queue.remove(theMap.get(key));
            V ret = theMap.get(key);
            theMap.remove(key);
            return ret;
        }
        return null;
    }
    
    public V takeHighestPriorityItem() {
        if(queue.isEmpty()) {
            return null;
        }
        return queue.pollLast();
    }
    
    public Iterator iterator() {
        return queue.iterator();
    }
    
    public V putBack(final V oldItem, final float forgetCycles, final Memory m) {
        final float relativeThreshold = m.narParameters.QUALITY_RESCALED;
        BudgetFunctions.applyForgetting(oldItem.budget, forgetCycles, relativeThreshold);
        return putIn(oldItem);
    }
    
    public boolean isEmpty() {
        return this.queue.isEmpty();
    }
}
