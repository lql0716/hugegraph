/*
 * Copyright 2017 HugeGraph Authors
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.baidu.hugegraph.traversal.optimize;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.BiPredicate;

import org.apache.tinkerpop.gremlin.process.traversal.Compare;
import org.apache.tinkerpop.gremlin.process.traversal.Contains;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.HasContainerHolder;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.RangeGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.IdentityStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.ElementValueComparator;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.process.traversal.util.AndP;
import org.apache.tinkerpop.gremlin.process.traversal.util.OrP;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;

import com.baidu.hugegraph.backend.BackendException;
import com.baidu.hugegraph.backend.query.Condition;
import com.baidu.hugegraph.backend.query.Condition.Relation;
import com.baidu.hugegraph.backend.query.ConditionQuery;
import com.baidu.hugegraph.backend.query.Query;
import com.baidu.hugegraph.type.define.HugeKeys;
import com.baidu.hugegraph.util.E;

public final class TraversalUtil {

    public static void extractHasContainer(HugeGraphStep<?, ?> newStep,
                                           Traversal.Admin<?, ?> traversal) {
        Step<?, ?> step = newStep;
        do {
            step = step.getNextStep();
            if (step instanceof HasStep) {
                HasContainerHolder holder = (HasContainerHolder) step;
                for (HasContainer has : holder.getHasContainers()) {
                    if (!GraphStep.processHasContainerIds(newStep, has)) {
                        newStep.addHasContainer(has);
                    }
                }
                TraversalHelper.copyLabels(step, step.getPreviousStep(), false);
                traversal.removeStep(step);
            }
        } while (step instanceof HasStep || step instanceof NoOpBarrierStep);
    }

    public static void extractHasContainer(HugeVertexStep<?> newStep,
                                           Traversal.Admin<?, ?> traversal) {
        Step<?, ?> step = newStep;
        do {
            if (step instanceof HasStep) {
                HasContainerHolder holder = (HasContainerHolder) step;
                for (HasContainer has : holder.getHasContainers()) {
                    newStep.addHasContainer(has);
                }
                TraversalHelper.copyLabels(step, step.getPreviousStep(), false);
                traversal.removeStep(step);
            }
            step = step.getNextStep();
        } while (step instanceof HasStep || step instanceof NoOpBarrierStep);
    }

    public static void extractOrder(Step<?, ?> newStep,
                                    Traversal.Admin<?, ?> traversal) {
        Step<?, ?> step = newStep;
        do {
            step = step.getNextStep();
            if (step instanceof OrderGlobalStep) {
                QueryHolder holder = (QueryHolder) newStep;
                @SuppressWarnings("resource")
                OrderGlobalStep<?, ?> orderStep = (OrderGlobalStep<?, ?>) step;
                orderStep.getComparators().forEach(comp -> {
                    ElementValueComparator<?> comparator =
                            (ElementValueComparator<?>) comp.getValue1();
                    holder.orderBy(comparator.getPropertyKey(),
                                   (Order) comparator.getValueComparator());
                });
                TraversalHelper.copyLabels(step, newStep, false);
                traversal.removeStep(step);
            }
            step = step.getNextStep();
        } while (step instanceof OrderGlobalStep ||
                 step instanceof IdentityStep);
    }

    public static void extractRange(Step<?, ?> newStep,
                                    Traversal.Admin<?, ?> traversal) {
        Step<?, ?> step = newStep;
        do {
            step = step.getNextStep();
            if (step instanceof RangeGlobalStep) {
                QueryHolder holder = (QueryHolder) newStep;
                RangeGlobalStep<?> range = (RangeGlobalStep<?>) step;
                holder.setRange(range.getLowRange(), range.getHighRange());

                /*
                 * NOTE: keep the step to filter results after query from DB
                 * due to the DB may not be implemented accurately.
                 */
                // TraversalHelper.copyLabels(step, newStep, false);
                // traversal.removeStep(step);
            }
        } while (step instanceof RangeGlobalStep ||
                 step instanceof IdentityStep ||
                 step instanceof NoOpBarrierStep);
    }

    public static ConditionQuery fillConditionQuery(
                                 List<HasContainer> hasContainers,
                                 ConditionQuery query) {
        for (HasContainer has : hasContainers) {
            BiPredicate<?, ?> bp = has.getPredicate().getBiPredicate();
            if (keyForContains(has.getKey())) {
                query.query(convContains2Condition(has));
            } else if (bp instanceof Compare) {
                query.query(convCompare2Relation(has));
            } else if (bp instanceof Contains) {
                query.query(convIn2Relation(has));
            } else if (has.getPredicate() instanceof AndP) {
                query.query(convAnd(has));
            } else if (has.getPredicate() instanceof OrP) {
                query.query(convOr(has));
            } else {
                // TODO: deal with other Predicate
                throw newUnsupportedPredicate(has.getPredicate());
            }
        }

        return query;
    }

    public static Condition convAnd(HasContainer has) {
        P<?> p = has.getPredicate();
        assert p instanceof AndP;
        @SuppressWarnings("unchecked")
        List<P<Object>> predicates = ((AndP<Object>) p).getPredicates();
        if (predicates.size() != 2) {
            throw newUnsupportedPredicate(p);
        }

        // Just for supporting P.inside() / P.between()
        return Condition.and(
                convCompare2Relation(
                        new HasContainer(has.getKey(), predicates.get(0))),
                convCompare2Relation(
                        new HasContainer(has.getKey(), predicates.get(1))));
    }

    public static Condition convOr(HasContainer has) {
        P<?> p = has.getPredicate();
        assert p instanceof OrP;
        // TODO: support P.outside() which is implemented by OR
        throw newUnsupportedPredicate(p);
    }

    public static Relation convCompare2Relation(HasContainer has) {
        BiPredicate<?, ?> bp = has.getPredicate().getBiPredicate();

        if (!(bp instanceof Compare)) {
            throw new IllegalArgumentException(
                      "Not support three layers or more logical conditions");
        }

        try {
            HugeKeys key = string2HugeKey(has.getKey());
            Object value = has.getValue();

            switch ((Compare) bp) {
                case eq:
                    return Condition.eq(key, value);
                case gt:
                    return Condition.gt(key, value);
                case gte:
                    return Condition.gte(key, value);
                case lt:
                    return Condition.lt(key, value);
                case lte:
                    return Condition.lte(key, value);
                case neq:
                    return Condition.neq(key, value);
            }
        } catch (IllegalArgumentException e) {
            String key = has.getKey();
            Object value = has.getValue();

            switch ((Compare) bp) {
                case eq:
                    return Condition.eq(key, value);
                case gt:
                    return Condition.gt(key, value);
                case gte:
                    return Condition.gte(key, value);
                case lt:
                    return Condition.lt(key, value);
                case lte:
                    return Condition.lte(key, value);
                case neq:
                    return Condition.neq(key, value);
            }
        }

        throw newUnsupportedPredicate(has.getPredicate());
    }

    public static Condition convIn2Relation(HasContainer has) {
        BiPredicate<?, ?> bp = has.getPredicate().getBiPredicate();
        assert bp instanceof Contains;
        List<?> value = (List<?>) has.getValue();

        try {
            HugeKeys key = string2HugeKey(has.getKey());

            switch ((Contains) bp) {
                case within:
                    return Condition.in(key, value);
                case without:
                    return Condition.nin(key, value);
            }
        } catch (IllegalArgumentException e) {
            String key = has.getKey();

            switch ((Contains) bp) {
                case within:
                    return Condition.in(key, value);
                case without:
                    return Condition.nin(key, value);
            }
        }

        throw newUnsupportedPredicate(has.getPredicate());
    }

    public static Condition convContains2Condition(HasContainer has) {
        BiPredicate<?, ?> bp = has.getPredicate().getBiPredicate();
        E.checkArgument(bp == Compare.eq,
                        "Not support CONTAINS query with relation '%s'", bp);

        HugeKeys key = string2HugeKey(has.getKey());
        Object value = has.getValue();

        if (keyForContainsKey(has.getKey())) {
            return Condition.containsKey(key, value);
        } else {
            assert keyForContainsValue(has.getKey());
            return Condition.contains(key, value);
        }
    }

    public static BackendException newUnsupportedPredicate(P<?> predicate) {
        return new BackendException("Unsupported predicate: '%s'", predicate);
    }

    public static HugeKeys string2HugeKey(String key) {
        if (key.equals(T.label.getAccessor())) {
            return HugeKeys.LABEL;
        } else if (key.equals(T.id.getAccessor())) {
            return HugeKeys.ID;
        } else if (keyForContains(key)) {
            return HugeKeys.PROPERTIES;
        }
        return HugeKeys.valueOf(key);
    }

    public static boolean keyForContains(String key) {
        return key.equals(T.key.getAccessor()) ||
               key.equals(T.value.getAccessor());
    }

    public static boolean keyForContainsKey(String key) {
        return key.equals(T.key.getAccessor());
    }

    public static boolean keyForContainsValue(String key) {
        return key.equals(T.value.getAccessor());
    }

    public static <V> Iterator<V> filterResult(
                                  List<HasContainer> hasContainers,
                                  Iterator<? extends Element> iterator) {
        final List<V> list = new ArrayList<>();

        while (iterator.hasNext()) {
            final Element elem = iterator.next();
            if (HasContainer.testAll(elem, hasContainers)) {
                @SuppressWarnings("unchecked")
                V e = (V) elem;
                list.add(e);
            }
        }
        return list.iterator();
    }

    public static Iterator<Edge> filterResult(Vertex vertex,
                                              Direction dir,
                                              Iterator<Edge> edges) {
        final List<Edge> list = new ArrayList<>();
        while (edges.hasNext()) {
            Edge edge = edges.next();
            if (dir == Direction.OUT && vertex.equals(edge.outVertex()) ||
                dir == Direction.IN && vertex.equals(edge.inVertex())) {
                list.add(edge);
            }
        }
        return list.iterator();
    }

    public static Query.Order convOrder(Order order) {
        return order == Order.decr ? Query.Order.DESC : Query.Order.ASC;
    }
}
