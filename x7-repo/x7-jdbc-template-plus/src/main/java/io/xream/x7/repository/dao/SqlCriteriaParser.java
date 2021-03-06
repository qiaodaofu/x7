/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.xream.x7.repository.dao;

import io.xream.x7.common.bean.*;
import io.xream.x7.common.bean.condition.RefreshCondition;
import io.xream.x7.common.util.BeanUtil;
import io.xream.x7.common.util.BeanUtilX;
import io.xream.x7.common.util.JsonX;
import io.xream.x7.common.util.StringUtil;
import io.xream.x7.common.web.Direction;
import io.xream.x7.repository.CriteriaParser;
import io.xream.x7.repository.SqlParsed;
import io.xream.x7.repository.exception.CriteriaSyntaxException;
import io.xream.x7.repository.exception.SqlBuildException;
import io.xream.x7.repository.mapper.Dialect;
import io.xream.x7.repository.util.SqlParserUtil;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.util.*;

public class SqlCriteriaParser implements CriteriaParser {

    @Autowired
    private Dialect dialect;

    public void setDialect(Dialect dialect) {
        this.dialect = dialect;
    }


    private void mapping(String script, Criteria criteria, StringBuilder sb) {
        String[] keyArr = script.split(SqlScript.SPACE);//
        int length = keyArr.length;
        for (int i = 0; i < length; i++) {
            String origin = keyArr[i].trim();

            String target = mapping(origin, criteria);
            sb.append(target).append(SqlScript.SPACE);

        }
    }

    private String mapping(String key, Criteria criteria) {

        if (key.contains(SqlScript.POINT)) {

            String[] arr = key.split("\\.");
            String alia = arr[0];
            String property = arr[1];

            String clzName = BeanUtilX.getClzName(alia, criteria);

            Parsed parsed = Parser.get(clzName);
            if (parsed == null)
                throw new RuntimeException("Entity Bean Not Exist: " + BeanUtil.getByFirstUpper(key));

            String value = parsed.getTableName(alia) + SqlScript.POINT + parsed.getMapper(property);

            return value;
        }

        if (criteria instanceof Criteria.ResultMappedCriteria) {
            Parsed parsed = Parser.get(key);
            if (parsed != null) {
                return parsed.getTableName();
            }
        }

        Class clz = criteria.getClz();
        Parsed parsed = Parser.get(clz);
        if (key.equals(BeanUtilX.getByFirstLower(parsed.getClz().getSimpleName())))
            return parsed.getTableName();
        String value = parsed.getMapper(key);
        if (value == null)
            return key;
        return value;

    }

    @Override
    public String parseCondition(CriteriaCondition criteriaCondition) {
        if (Objects.isNull(criteriaCondition))
            return "";
        StringBuilder sb = new StringBuilder();
        List<Criteria.X> xList = criteriaCondition.getListX();
        x(sb, xList, criteriaCondition, true);
        return sb.toString();
    }

    @Override
    public SqlParsed parse(Criteria criteria) {

        parseAlia(criteria);

        env(criteria);

        resultKey(criteria);
        /*
         * select column
         */
        StringBuilder sb = new StringBuilder();
        select(sb, criteria);
        /*
         * from table
         */
        sourceScript(sb, criteria);
        /*
         * force index
         */
        forceIndex(sb, criteria);

        filter(criteria);
        /*
         * StringList
         */
        x(sb, criteria.getListX(), criteria);

        StringBuilder countSql = count(sb, criteria);
        /*
         * group by
         */
        groupBy(sb, criteria);

        having(sb, criteria);
        /*
         * sort
         */
        sort(sb, criteria);

        SqlParsed sqlParsed = sqlArr(sb, criteria, countSql);

        return sqlParsed;
    }

    @Override
    public String parseRefresh(Parsed parsed, RefreshCondition refreshCondition) {

        StringBuilder sb = new StringBuilder();
        sb.append(SqlScript.UPDATE).append(SqlScript.SPACE).append(parsed.getTableName()).append(SqlScript.SPACE);

        concatRefresh(sb, parsed, refreshCondition);

        filterCondition(parsed, refreshCondition.getListX());

        String conditionSql = parseCondition(refreshCondition);

        if (conditionSql.startsWith(" AND")|| conditionSql.startsWith("AND")) {
            conditionSql = conditionSql.replaceFirst("AND", "WHERE");
        }

        conditionSql = SqlParserUtil.mapper(conditionSql, parsed);

        sb.append(conditionSql);

        String sql = sb.toString();

        if (sql.contains("SET  WHERE"))
            throw new SqlBuildException(sql);

        return sql;
    }

    private void filterCondition(Parsed parsed,List<Criteria.X> listX){
        Iterator<Criteria.X> ite = listX.iterator();
        while(ite.hasNext()){
            Criteria.X x = ite.next();
            if (BeanUtilX.isBaseType_0(x.getKey(), x.getValue(), parsed)){
                ite.remove();
            }
        }

    }


    private void concatRefresh(StringBuilder sb, Parsed parsed, RefreshCondition refreshCondition) {

        sb.append(SqlScript.SET);

        List<Criteria.X> refreshList = refreshCondition.getRefreshList();

        List<Object> refreshValueList = new ArrayList<>();

        boolean isNotFirst = false;
        for (Criteria.X x : refreshList) {

            if (x.getPredicate() == PredicateAndOtherScript.X) {

                if (isNotFirst) {
                    sb.append(SqlScript.COMMA).append(SqlScript.SPACE);
                }

                isNotFirst = true;

                Object key = x.getKey();

                String str = key.toString();

                String sql = BeanUtilX.normalizeSql(str);

                sql = SqlParserUtil.mapper(sql, parsed);

                sb.append(sql);

            } else {
                String key = x.getKey();
                if (key.contains("?")) {

                    if (isNotFirst) {
                        sb.append(SqlScript.COMMA).append(SqlScript.SPACE);
                    }

                    isNotFirst = true;

                    String sql = BeanUtilX.normalizeSql(key);
                    sql = SqlParserUtil.mapper(sql, parsed);
                    sb.append(sql);
                } else {

                    if (StringUtil.isNullOrEmpty(x.getValue().toString()) || BeanUtilX.isBaseType_0(key, x.getValue(), parsed)) {
                        continue;
                    }

                    if (isNotFirst) {
                        sb.append(SqlScript.COMMA).append(SqlScript.SPACE);
                    }

                    isNotFirst = true;

                    String mapper = parsed.getMapper(key);
                    sb.append(mapper);
                    sb.append(SqlScript.EQ_PLACE_HOLDER);

                    BeanElement be = parsed.getElementMap().get(key);
                    if (be == null) {
                        throw new RuntimeException("can not find the property " + key + " of " + parsed.getClzName());
                    }
                    if (be.clz == Date.class) {
                        if (x.getValue() instanceof Long) {
                            x.setValue(new Date(((Long) x.getValue()).longValue()));
                        }
                    } else if (be.clz == Timestamp.class) {
                        if (x.getValue() instanceof Long) {
                            x.setValue(new Timestamp(((Long) x.getValue()).longValue()));
                        }
                    } else if (be.isJson) {
                        Object v = x.getValue();
                        if (v != null) {
                            String str = JsonX.toJson(v);
                            x.setValue(str);
                        }
                    }

                }

                refreshValueList.add(x.getValue());
            }

        }


        if (!refreshValueList.isEmpty()) {
            refreshCondition.getValueList().addAll(0, refreshValueList);
        }
    }

    private SqlParsed sqlArr(StringBuilder sb, Criteria criteria, StringBuilder countSql) {

        SqlParsed sqlParsed = new SqlParsed();

        sqlParsed.setCountSql(countSql == null ? null : countSql.toString());

        StringBuilder sqlSb = new StringBuilder();
        sqlSb.append(SqlScript.SELECT).append(SqlScript.SPACE).append(criteria.resultAllScript()).append(SqlScript.SPACE).append(sb);

        sqlParsed.setSql(sqlSb);

        return sqlParsed;
    }


    private void env(Criteria criteria) {
        if (criteria instanceof Criteria.ResultMappedCriteria) {
            Criteria.ResultMappedCriteria resultMapped = (Criteria.ResultMappedCriteria) criteria;
            PropertyMapping propertyMapping = resultMapped.getPropertyMapping();//
            if (Objects.isNull(propertyMapping)) {
                propertyMapping = new PropertyMapping();
                resultMapped.setPropertyMapping(propertyMapping);
            }
        }
        criteria.getValueList().clear();
    }

    private void resultKey(Criteria criteria) {
        if (!(criteria instanceof Criteria.ResultMappedCriteria))
            return;

        boolean flag = false;

        Criteria.ResultMappedCriteria resultMapped = (Criteria.ResultMappedCriteria) criteria;
        StringBuilder column = new StringBuilder();

        PropertyMapping propertyMapping = resultMapped.getPropertyMapping();

        if (Objects.nonNull(resultMapped.getDistinct())) {

            if (!flag) resultMapped.getResultKeyList().clear();//去掉构造方法里设置的返回key

            column.append(SqlScript.DISTINCT);
            List<String> list = resultMapped.getDistinct().getList();
            int size = list.size();
            int i = 0;
            StringBuilder distinctColumn = new StringBuilder();
            distinctColumn.append(column);
            for (String resultKey : list) {

                String mapper = mapping(resultKey, criteria);
                propertyMapping.put(resultKey, mapper);//REDUCE ALIAN NAME
                distinctColumn.append(SqlScript.SPACE).append(mapper);
                mapper = this.dialect.resultKeyAlian(mapper, resultMapped);
                column.append(SqlScript.SPACE).append(mapper);
                i++;
                if (i < size) {
                    column.append(SqlScript.COMMA);
                    distinctColumn.append(SqlScript.COMMA);
                }
            }
            criteria.setCountDistinct("COUNT(" + distinctColumn.toString() + ") count");
            flag = true;
        }

        List<Reduce> reduceList = resultMapped.getReduceList();

        if (!reduceList.isEmpty()) {

            if (!flag) resultMapped.getResultKeyList().clear();//去掉构造方法里设置的返回key

            for (Reduce reduce : reduceList) {
                if (flag) {
                    column.append(SqlScript.COMMA);
                }
                String alianProperty = reduce.getProperty() + SqlScript.UNDER_LINE + reduce.getType().toString().toLowerCase();//property_count
                String alianName = alianProperty.replace(SqlScript.POINT, SqlScript.DOLLOR);
                resultMapped.getResultKeyAliaMap().put(alianName, alianProperty);

                String value = mapping(reduce.getProperty(), criteria);

                ReduceType reduceType = reduce.getType();
                if (reduceType == ReduceType.GROUP_CONCAT_DISTINCT){
                    reduceType = ReduceType.GROUP_CONCAT;
                    value = "DISTINCT " + value;
                }

                column.append(SqlScript.SPACE)
                        .append(reduceType)
                        .append(SqlScript.LEFT_PARENTTHESIS)//" ( "
                        .append(value)
                        .append(SqlScript.RIGHT_PARENTTHESIS).append(SqlScript.SPACE)//" ) "
                        .append(SqlScript.AS).append(SqlScript.SPACE).append(alianName);

                Criteria.X x = reduce.getHaving();
                if (x != null) {
                    x.setKey(alianName);
                    if (!criteria.isTotalRowsIgnored()){
                        throw new CriteriaSyntaxException("Reduce with having not support totalRows query, try to build.paged().scroll(true)");
                    }
                }

                flag = true;
            }
        }

        List<String> resultList = resultMapped.getResultKeyList();
        if (!resultList.isEmpty()) {
            if (flag) {
                column.append(SqlScript.COMMA);
            }
            int size = resultList.size();
            for (int i = 0; i < size; i++) {
                String key = resultList.get(i);
                String mapper = mapping(key, criteria);
                propertyMapping.put(key, mapper);
                mapper = this.dialect.resultKeyAlian(mapper, resultMapped);
                column.append(SqlScript.SPACE).append(mapper);
                if (i < size - 1) {
                    column.append(SqlScript.COMMA);
                }
            }
        }

        String script = column.toString();
        if (StringUtil.isNullOrEmpty(script)) {
            throw new CriteriaSyntaxException("Suggest API: find(Criteria criteria), no any resultKey for ResultMappedCriteria");
        }
        criteria.setCustomedResultKey(column.toString());
    }

    private void select(StringBuilder sb, Criteria criteria) {

    }

    private void groupBy(StringBuilder sb, Criteria criteria) {
        if (criteria instanceof Criteria.ResultMappedCriteria) {
            Criteria.ResultMappedCriteria rm = (Criteria.ResultMappedCriteria) criteria;

            String groupByS = rm.getGroupBy();
            if (StringUtil.isNullOrEmpty(groupByS))
                return;

            sb.append(ConjunctionAndOtherScript.GROUP_BY.sql());

            String[] arr = groupByS.split(SqlScript.COMMA);

            int i = 0, l = arr.length;
            for (String groupBy : arr) {
                groupBy = groupBy.trim();
                if (StringUtil.isNotNull(groupBy)) {
                    String mapper = mapping(groupBy, criteria);
                    sb.append(mapper);
                    i++;
                    if (i < l) {
                        sb.append(SqlScript.COMMA);
                    }
                }
            }
        }
    }

    private void having(StringBuilder sb, Criteria criteria) {
        if (!(criteria instanceof Criteria.ResultMappedCriteria))
            return;

        Criteria.ResultMappedCriteria resultMapped = (Criteria.ResultMappedCriteria)criteria;
        List<Reduce> reduceList = resultMapped.getReduceList();

        if (reduceList.isEmpty())
            return;
        for (Reduce reduce : reduceList) {
            Criteria.X x = reduce.getHaving();
            if (x == null)
                continue;
            sb.append(x.getConjunction().sql()).append(x.getKey()).append(x.getPredicate().sql()).append(x.getValue());
        }
    }


    private void parseAlia(Criteria criteria) {
        String script = criteria.sourceScript().trim();

        if (criteria instanceof Criteria.ResultMappedCriteria) {
            Criteria.ResultMappedCriteria resultMappedCriteria = (Criteria.ResultMappedCriteria) criteria;
            Map<String, String> aliaMap = BeanUtilX.parseAliaBySourceScriptSql(script);
            resultMappedCriteria.setAliaMap(aliaMap);
        }
    }

    private void sourceScript(StringBuilder sb, Criteria criteria) {
        String script = criteria.sourceScript().trim();

        if (script.startsWith(SqlScript.FROM) || script.startsWith(SqlScript.FROM.toLowerCase())) {
            script = script.replaceFirst(SqlScript.FROM, SqlScript.NONE);
        }

        sb.append(SqlScript.SPACE).append(SqlScript.FROM).append(SqlScript.SPACE);

        mapping(script, criteria, sb);
    }

    private void forceIndex(StringBuilder sb, Criteria criteria) {
        if (StringUtil.isNullOrEmpty(criteria.getForceIndex()))
            return;
        sb.append("FORCE INDEX(" + criteria.getForceIndex() + ")");
    }

    private StringBuilder count(StringBuilder sb, Criteria criteria) {
        if (!criteria.isTotalRowsIgnored()) {
            StringBuilder countSb = new StringBuilder();
            countSb.append(SqlScript.SELECT).append(SqlScript.SPACE).append(criteria.getCountDistinct()).append(SqlScript.SPACE).append(sb);
            return countSb;
        }
        return null;
    }

    private void sort(StringBuilder sb, Criteria criteria) {

        if (criteria.isFixedSort())
            return;

        List<Sort> sortList = criteria.getSortList();
        if (sortList != null && !sortList.isEmpty()) {

            sb.append(ConjunctionAndOtherScript.ORDER_BY.sql());
            int size = sortList.size();
            int i = 0;
            for (Sort sort : sortList) {
                String orderBy = sort.getOrderBy();
                String mapper = mapping(orderBy, criteria);
                sb.append(mapper).append(SqlScript.SPACE);
                Direction direction = sort.getDirection();
                if (direction == null) {
                    sb.append(Direction.DESC);
                } else {
                    sb.append(direction);
                }
                i++;
                if (i < size) {
                    sb.append(SqlScript.COMMA).append(SqlScript.SPACE);
                }
            }
        }

    }

    private void x(StringBuilder sb, List<Criteria.X> xList, CriteriaCondition criteria, boolean isWhere) {
        for (Criteria.X x : xList) {

            if (x.getPredicate() == PredicateAndOtherScript.X) {
                appendConjunction(sb, x, criteria, isWhere);
                sb.append(x.getKey());
                Object valueObject = x.getValue();
                if (valueObject != null) {
                    if (valueObject instanceof List) {
                        criteria.getValueList().addAll((List<Object>) valueObject);
                    } else {
                        criteria.getValueList().add(valueObject);
                    }
                }
                continue;
            }


            Object v = x.getValue();
            if (Objects.isNull(v))
                continue;


            if (Objects.nonNull(x.getConjunction())) {

                List<Criteria.X> subList = x.getSubList();
                if (x.getSubList() != null) {
                    StringBuilder xSb = new StringBuilder();

                    x(xSb, subList, criteria, false);//sub concat

                    String script = xSb.toString();
                    if (StringUtil.isNotNull(script)) {
                        final String and = ConjunctionAndOtherScript.AND.sql();
                        final String or = ConjunctionAndOtherScript.OR.sql();
                        if (script.startsWith(and)) {
                            script = script.replaceFirst(and, SqlScript.NONE);
                        } else if (script.startsWith(or)) {
                            script = script.replaceFirst(or, SqlScript.NONE);
                        }
                        x.setScript(PredicateAndOtherScript.SUB_BEGIN.sql() + script + PredicateAndOtherScript.SUB_END.sql());
                    }
                }

            }

            if (PredicateAndOtherScript.SUB_BEGIN == x.getPredicate()) {
                continue;
            } else if (PredicateAndOtherScript.SUB_END == x.getPredicate()) {
                continue;
            }

            if (StringUtil.isNotNull(x.getKey())) {
                if (x.getKey().equals(PredicateAndOtherScript.SUB.sql())) {
                    if (Objects.nonNull(x.getScript())) {

                        appendConjunction(sb, x, criteria, isWhere);
                        sb.append(x.getScript());
                    }
                    continue;
                }
            }
            x(x, criteria, isWhere);

            if (Objects.nonNull(x.getScript())) {
                sb.append(x.getScript());
            }
        }

    }

    private void filter(Criteria criteria){
        if (criteria instanceof Criteria.ResultMappedCriteria){
            List<Criteria.X> xList = criteria.getListX();
            filter0(xList,criteria);
        }
    }

    private void filter0(List<Criteria.X> xList, Criteria criteria){
        Iterator<Criteria.X> ite = xList.iterator();
        while (ite.hasNext()) {
            Criteria.X x = ite.next();
            PredicateAndOtherScript p = x.getPredicate();
            String key = x.getKey();
            if (p == PredicateAndOtherScript.EQ
                    || p == PredicateAndOtherScript.NE
                    || p == PredicateAndOtherScript.GT
                    || p == PredicateAndOtherScript.GTE
                    || p == PredicateAndOtherScript.LT
                    || p == PredicateAndOtherScript.LTE) {

                if (key.contains(".")){
                    String[] arr = key.split("\\.");
                    String alia = arr[0];
                    String clzName = criteria.getAliaMap().get(alia);
                    Parsed parsed = Parser.get(clzName);
                    if (BeanUtilX.isBaseType_0(arr[1],x.getValue(),parsed)){
                        ite.remove();
                    }
                }else{
                    Parsed parsed = criteria.getParsed();
                    if (BeanUtilX.isBaseType_0(key,x.getValue(),parsed)){
                        ite.remove();
                    }
                }
            }else if (p == PredicateAndOtherScript.IN
            || p == PredicateAndOtherScript.NOT_IN) {

                List valueList = (List)x.getValue();
                if (valueList.size() > 1)
                    continue;

                if (key.contains(".")){
                    String[] arr = key.split("\\.");
                    String alia = arr[0];
                    String clzName = criteria.getAliaMap().get(alia);
                    Parsed parsed = Parser.get(clzName);

                    if (BeanUtilX.isBaseType_0(arr[1],valueList.get(0),parsed)){
                        ite.remove();
                    }

                }else{
                    Parsed parsed = criteria.getParsed();
                    if (BeanUtilX.isBaseType_0(key,valueList.get(0),parsed)){
                        ite.remove();
                    }
                }

            }
            List<Criteria.X> subList = x.getSubList();
            if (subList == null || subList.isEmpty())
                continue;
            filter0(subList,criteria);
        }
    }

    private void x(StringBuilder sb, List<Criteria.X> xList, Criteria criteria) {

        StringBuilder xsb = new StringBuilder();
        x(xsb, xList, criteria, true);

        String script = xsb.toString();

        mapping(script, criteria, sb);

    }


    private void appendConjunction(StringBuilder sb, Criteria.X x, CriteriaCondition criteriaBuilder, boolean isWhere) {
        if (Objects.isNull(x.getConjunction()))
            return;
        if (criteriaBuilder instanceof Criteria) {
            Criteria criteria = (Criteria) criteriaBuilder;
            if (isWhere && criteria.isWhere()) {
                criteria.setWhere(false);
                sb.append(ConjunctionAndOtherScript.WHERE.sql());
            } else {
                sb.append(x.getConjunction().sql());
            }
        } else {
            sb.append(x.getConjunction().sql());
        }
    }

    private void x(Criteria.X x, CriteriaCondition criteria, boolean isWhere) {

        StringBuilder sb = new StringBuilder();
        PredicateAndOtherScript p = x.getPredicate();
        Object v = x.getValue();

        if (p == PredicateAndOtherScript.IN || p == PredicateAndOtherScript.NOT_IN) {

            appendConjunction(sb, x, criteria, isWhere);

            sb.append(x.getKey()).append(p.sql());
            List<Object> inList = (List<Object>) v;
            in(sb, inList);
        } else if (p == PredicateAndOtherScript.BETWEEN) {

            appendConjunction(sb, x, criteria, isWhere);

            sb.append(x.getKey()).append(p.sql());
            between(sb);

            MinMax minMax = (MinMax) v;
            List<Object> valueList = criteria.getValueList();
            valueList.add(minMax.getMin());
            valueList.add(minMax.getMax());

        } else if (p == PredicateAndOtherScript.IS_NOT_NULL || p == PredicateAndOtherScript.IS_NULL) {

            appendConjunction(sb, x, criteria, isWhere);

            sb.append(v).append(p.sql());

        } else {
            if (StringUtil.isNullOrEmpty(x.getKey()))
                return;

            appendConjunction(sb, x, criteria, isWhere);

            Class clz = v.getClass();
            sb.append(x.getKey()).append(x.getPredicate().sql());
            if (clz == String.class) {
                String str = v.toString();
                if (str.startsWith(SqlScript.WELL_NO) && str.endsWith(SqlScript.WELL_NO)) {
                    str = str.replace(SqlScript.WELL_NO, SqlScript.NONE);
                    sb.append(str);
                    return;
                } else {
                    sb.append(SqlScript.PLACE_HOLDER);
                }
            } else {
                sb.append(SqlScript.PLACE_HOLDER);
            }

            //if (BeanUtil.isEnum(clz) || BeanUtil.isEnum(clz.getSuperclass())) {
            if (BeanUtil.isEnum(clz)) {
                try {
                    criteria.getValueList().add(((Enum) v).name());
                } catch (Exception e) {

                }
            } else {
                criteria.getValueList().add(v);
            }
        }
        x.setScript(sb.toString());
    }

    private void between(StringBuilder sb) {

        sb.append(SqlScript.PLACE_HOLDER).append(ConjunctionAndOtherScript.AND.sql()).append(SqlScript.PLACE_HOLDER);

    }

    private void in(StringBuilder sb, List<Object> inList) {

        if (inList == null || inList.isEmpty())
            return;

        Object v = inList.get(0);

        Class<?> vType = v.getClass();

        SqlUtil.buildIn(sb,vType,inList);

    }


}
