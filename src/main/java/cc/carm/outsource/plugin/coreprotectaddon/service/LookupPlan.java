package cc.carm.outsource.plugin.coreprotectaddon.service;

import cc.carm.outsource.plugin.coreprotectaddon.api.lookup.*;
import cc.carm.outsource.plugin.coreprotectaddon.command.LookupParameters;
import java.sql.*;
import java.util.*;
import static cc.carm.outsource.plugin.coreprotectaddon.service.CoreProtectQueryService.*;

/** CoreProtect 24 lookup families projected into a common row shape before counting/paging.
 * Binary item metadata is deliberately fetched only for the returned page/candidate batch. */
final class LookupPlan {
    private final TimedSelect sql;
    private final Tables tables;
    private final LookupRequest request;
    private final QueryLimits limits;
    private final ComponentMatcher matcher;
    private final int candidateLimit;
    private final List<Object> parameters = new ArrayList<>();
    private final List<String> sources = new ArrayList<>();
    private final List<Long> users, excludedUsers = new ArrayList<>(), includedMaterials, excludedMaterials = new ArrayList<>();
    private final List<Long> includedEntities, excludedEntities = new ArrayList<>();
    private final Long world;
    private final long[] window;
    private boolean entityInclude;

    LookupPlan(TimedSelect sql,Tables tables,LookupRequest request,QueryLimits limits,long[] window,
               ComponentMatcher matcher,int candidateLimit) throws SQLException {
        this.sql=sql; this.tables=tables; this.request=request; this.limits=limits;
        this.window=window; this.matcher=matcher; this.candidateLimit=candidateLimit;
        if (request.options().unresolved()) throw error("UNRESOLVED_LOCATION","空间参数须先由命令线程解析；Java API 请传入 SpatialBounds。");
        for (List<String> list : List.of(request.users(),request.include(),request.exclude()))
            if (list.size()>4096 || list.stream().anyMatch(String::isBlank)) throw error("INVALID_PARAMETER","条件列表无效或超过 4096 项。");
        if (request.users().size()>100) throw error("INVALID_PARAMETER","玩家列表最多 100 项。");
        if (!request.action().hasMaterials() && !request.include().isEmpty()) throw error("INCOMPATIBLE_PARAMETER","此类型不支持 i: 条件。");
        if (request.action().inventory() && request.users().isEmpty()) throw error("MISSING_USER","inventory 查询必须提供 u:<玩家>。");
        if (request.action()==LookupAction.USERNAME && request.options().bounds()!=null) throw error("INCOMPATIBLE_PARAMETER","username 记录没有位置。");
        if (request.content()!=null && !request.action().isItem() && !request.action().messages())
            throw error("INCOMPATIBLE_PARAMETER","此类型不支持 content: 条件。");
        if (request.action().isItem() && request.content()!=null) ItemContent.parse(request.content());
        Map<String,Long> userMap = ids(tables.users(),"rowid","user",request.users());
        if (request.action()!=LookupAction.USERNAME) for (String user:request.users())
            if (!userMap.containsKey(lower(user))) throw error("UNKNOWN_USER","找不到玩家："+user);
        users=new ArrayList<>(userMap.values());
        Map<String,Long> materials=ids(tables.materials(),"id","material",request.include().stream().map(LookupParameters::material).toList());
        includedMaterials=new ArrayList<>(materials.values());
        boolean entitiesAllowed=request.action()==LookupAction.ALL || request.action()==LookupAction.KILL;
        List<String> entityNames=request.include().stream().map(LookupPlan::entityName).toList();
        includedEntities=entitiesAllowed && !entityNames.isEmpty()
                ? new ArrayList<>(ids(tables.entities(),"id","entity",entityNames).values()) : new ArrayList<>();
        // CoreProtect stores player kills as type=0, data=<victim's user ID>.
        if (entitiesAllowed && entityNames.contains("player")) includedEntities.add(0L);
        entityInclude=!includedEntities.isEmpty();
        if (entityInclude && !includedMaterials.isEmpty()) throw error("INCOMPATIBLE_PARAMETER","i: 不能混合方块/物品与实体。");
        if (request.action()==LookupAction.KILL && !includedMaterials.isEmpty()) throw error("INCOMPATIBLE_PARAMETER","kill 的 i: 应填写实体类型。");
        for (String value:request.exclude()) {
            Map<String,Long> material=value.startsWith("#") ? Map.of() : ids(tables.materials(),"id","material",List.of(LookupParameters.material(value)));
            if (!material.isEmpty()) {
                if (!request.action().hasMaterials()) throw error("INCOMPATIBLE_PARAMETER","此类型只能按玩家排除。");
                excludedMaterials.addAll(material.values()); continue;
            }
            Map<String,Long> entity=entitiesAllowed && !value.startsWith("#") ? ids(tables.entities(),"id","entity",List.of(entityName(value))) : Map.of();
            if (entitiesAllowed && entityName(value).equals("player")) { excludedEntities.add(0L); continue; }
            if (!entity.isEmpty()) { excludedEntities.addAll(entity.values()); continue; }
            Map<String,Long> player=ids(tables.users(),"rowid","user",List.of(value));
            if (player.isEmpty()) throw error("UNKNOWN_EXCLUDE","找不到排除的物品、实体或玩家："+value);
            excludedUsers.addAll(player.values());
        }
        if (request.action().inventory()) {
            excludedMaterials.addAll(ids(tables.materials(),"id","material",List.of("minecraft:fire","minecraft:water","minecraft:farmland")).values());
            excludedUsers.addAll(ids(tables.users(),"rowid","user",List.of("#hopper")).values());
        }
        SpatialBounds bounds=request.options().bounds();
        if (bounds==null) world=null;
        else {
            Map<String,Long> worlds=ids(tables.worlds(),"id","world",List.of(bounds.world()));
            world=worlds.get(lower(bounds.world()));
            if (world==null) throw error("UNKNOWN_WORLD","查询数据库中找不到世界："+bounds.world());
        }
    }

    LookupResult execute(int page,int size,int offset,long started) throws SQLException {
        LookupAction action=request.action();
        if (action==LookupAction.ALL) {
            branch("block",0,entityInclude ? List.of(3) : List.of(),List.of());
            if (!entityInclude) { branch("container",1,List.of(),List.of()); branch("item",2,List.of(),List.of(8,9,10,11,12)); }
        } else if (action.inventory()) {
            if (action!=LookupAction.INVENTORY_ADD) branch("block",0,List.of(1),List.of());
            branch("container",1,action==LookupAction.INVENTORY ? List.of() : List.of(action==LookupAction.INVENTORY_ADD ? 0 : 1),List.of());
            branch("item",2,action==LookupAction.INVENTORY ? List.of() : action==LookupAction.INVENTORY_ADD
                    ? List.of(0,3,4,10,12) : List.of(1,2,5,6,7,8,9,11),List.of());
        } else branch(action.family(), sourceOrder(action.family()), action==LookupAction.ITEM ? List.of() : action.actions(),
                action==LookupAction.ITEM ? List.of(8,9,10,11,12) : List.of());
        String base=" FROM ("+String.join(" UNION ALL ",sources)+") coq_lookup";
        if (request.action().isItem() && request.content()!=null) return components(base,page,size,offset,started);
        int found=sql.read("SELECT COUNT(*) FROM (SELECT `rowid`"+base+" LIMIT "+(limits.maxResults()+1)+") coq_count",parameters,rs->{rs.next();return rs.getInt(1);});
        int total=Math.min(found,limits.maxResults());
        checkPage(total,page,offset);
        List<LookupRecord> records=List.of();
        if (!request.options().countOnly() && total>0) records=load("SELECT *"+base+order()+" LIMIT "+Math.min(size,total-offset)+" OFFSET "+offset,parameters);
        return result(page,size,total,found>limits.maxResults(),records,started);
    }

    private void branch(String source,int sourceOrder,List<Integer> actions,List<Integer> excludedActions) throws SQLException {
        boolean username=source.equals("username_log"), block=source.equals("block"), item=source.equals("item") || source.equals("container");
        boolean typed=block || item;
        StringBuilder where=new StringBuilder(" WHERE `time` >= ? AND `time` <= ?");
        List<Object> values=new ArrayList<>(List.of(window[0],window[1]));
        if (username) {
            if (!request.users().isEmpty()) {
                // Name changes are linked through UUID, not the current user display name.
                List<String> uuids=sql.read("SELECT `uuid` FROM "+quote(tables.users())+" WHERE LOWER(`user`) IN ("+marks(request.users().size())+") OR `uuid` IN ("+marks(request.users().size())+")",
                        java.util.stream.Stream.concat(request.users().stream().map(LookupPlan::lower),request.users().stream()).toList(),rs->{List<String> out=new ArrayList<>();while(rs.next()) if(rs.getString(1)!=null)out.add(rs.getString(1));return out;});
                if (uuids.isEmpty()) throw error("UNKNOWN_USER","找不到玩家 UUID。");
                in(where,values,"uuid",uuids,false);
            }
            if (!excludedUsers.isEmpty()) {
                List<String> uuids=sql.read("SELECT `uuid` FROM "+quote(tables.users())+" WHERE `rowid` IN ("+marks(excludedUsers.size())+")",excludedUsers,
                        rs->{List<String> out=new ArrayList<>();while(rs.next()) if(rs.getString(1)!=null)out.add(rs.getString(1));return out;});
                in(where,values,"uuid",uuids,true);
            }
        } else {
            in(where,values,"user",users,false); in(where,values,"user",excludedUsers,true);
            if (world!=null) {
                where.append(" AND `wid` = ?"); values.add(world);
                SpatialBounds b=request.options().bounds();
                if (b.minX()!=null) {
                    where.append(" AND `x` BETWEEN ? AND ? AND `z` BETWEEN ? AND ?");
                    values.addAll(List.of(b.minX(),b.maxX(),b.minZ(),b.maxZ()));
                    if(b.minY()!=null) {where.append(" AND `y` BETWEEN ? AND ?");values.addAll(List.of(b.minY(),b.maxY()));}
                }
            }
        }
        in(where,values,"action",actions,false); in(where,values,"action",excludedActions,true);
        if (typed) {
            boolean entity=block && (request.action()==LookupAction.KILL || entityInclude);
            List<Long> includes=entity ? includedEntities : includedMaterials;
            if (!request.include().isEmpty() && includes.isEmpty()) where.append(" AND 1=0");
            else in(where,values,"type",includes,false);
            if (block) {
                if (!includedMaterials.isEmpty()) where.append(" AND `action` <> 3");
                conditionalExclude(where,values,excludedMaterials,"`action` <> 3");
                conditionalExclude(where,values,excludedEntities,"`action` = 3");
            } else in(where,values,"type",excludedMaterials,true);
        }
        String message=source.equals("chat") || source.equals("command") ? "`message`" : "''";
        if (source.equals("sign")) {
            StringBuilder front=new StringBuilder("CONCAT("),back=new StringBuilder("CONCAT(");
            StringJoiner nonempty=new StringJoiner(" OR "," AND (",")");
            for(int i=1;i<=8;i++) {
                StringBuilder side=i<=4 ? front : back;
                if(i!=1 && i!=5)side.append(",' ',");
                side.append("COALESCE(`line_").append(i).append("`,'')");
                nonempty.add("LENGTH(`line_"+i+"`) > 0");
            }
            message="CASE WHEN `face` = 0 THEN "+front+") ELSE "+back+") END";
            where.append(nonempty);
        }
        if(request.content()!=null && request.action().messages()) {
            where.append(sql.clickhouse ? " AND match("+message+", ?)" : sql.duckdb ? " AND regexp_matches("+message+", ?)" : " AND "+message+" REGEXP ?");
            values.add(request.content());
        }
        String columns="`rowid`,`time`,"+(username ? "0 AS `user`,0 AS `wid`,NULL AS `x`,NULL AS `y`,NULL AS `z`," : "`user`,`wid`,`x`,`y`,`z`,")
                +(typed ? "`type`," : "0 AS `type`,")+(item ? "`amount`," : "1 AS `amount`,")
                +(typed || source.equals("session") || source.equals("sign") ? "`action`," : "0 AS `action`,")
                +(block ? "`data`," : "0 AS `data`,")
                +(typed ? "`rolled_back`," : "0 AS `rolled_back`,")
                +(username ? "`user`" : message)+" AS `message`,"+(username ? "`uuid`" : "''")+" AS `uuid`,"
                +sourceOrder+" AS `source_order`,'"+source+"' AS `source`";
        sources.add("SELECT "+columns+" FROM "+quote(table(source))+where); parameters.addAll(values);
    }

    private List<LookupRecord> load(String query,List<?> values) throws SQLException {
        List<Row> rows=sql.read(query,values,rs->{List<Row> out=new ArrayList<>();while(rs.next())out.add(new Row(rs.getLong("rowid"),rs.getLong("time"),rs.getLong("user"),rs.getLong("wid"),
                nullable(rs,"x"),nullable(rs,"y"),nullable(rs,"z"),rs.getLong("type"),rs.getInt("amount"),rs.getInt("action"),rs.getLong("data"),rs.getInt("rolled_back"),rs.getString("message"),rs.getString("uuid"),rs.getString("source"),rs.getInt("source_order")));return out;});
        Map<Long,String> names=labels(tables.users(),"rowid","user",rows.stream().flatMap(r->java.util.stream.Stream.of(r.user,r.data)).toList());
        Map<Long,String> worlds=labels(tables.worlds(),"id","world",rows.stream().map(Row::world).toList());
        Map<Long,String> materials=labels(tables.materials(),"id","material",rows.stream().filter(r->r.typed() && !r.entity()).map(Row::type).toList());
        Map<Long,String> entities=labels(tables.entities(),"id","entity",rows.stream().filter(Row::entity).map(Row::type).filter(id->id!=0).toList());
        Map<String,Map<Long,ItemSnapshot>> snapshots=new HashMap<>();
        for(String source:List.of("item","container")) {
            List<Long> ids=rows.stream().filter(r->r.source.equals(source)).map(Row::id).toList();
            if(ids.isEmpty())continue;
            String column=source.equals("item")?"data":"metadata";
            snapshots.put(source,sql.read("SELECT `rowid`,`"+column+"` AS `item_metadata` FROM "+quote(table(source))+" WHERE `rowid` IN ("+marks(ids.size())+")",ids,rs->{Map<Long,ItemSnapshot> out=new HashMap<>();while(rs.next())out.put(rs.getLong("rowid"),new ItemSnapshot(readItemMetadata(rs,sql.clickhouse)));return out;}));
        }
        List<LookupRecord> records=new ArrayList<>();
        for(Row r:rows) {
            String material=r.entity() ? (r.type==0 ? names.getOrDefault(r.data,"未知玩家 #"+r.data) : entities.getOrDefault(r.type,"未知实体 #"+r.type)) : materials.getOrDefault(r.type,"未知类型 #"+r.type);
            records.add(new LookupRecord(r.id,r.time,r.user,r.source.equals("username_log") ? r.message : names.getOrDefault(r.user,"未知玩家 #"+r.user),
                    r.source.equals("username_log") ? "无位置" : worlds.getOrDefault(r.world,"未知世界 #"+r.world),r.x,r.y,r.z,
                    r.source.equals("username_log") ? r.uuid : r.typed() ? null : r.message,material,r.amount,r.action,snapshots.getOrDefault(r.source,Map.of()).get(r.id),r.source,r.rolledBack));
        }
        return records;
    }

    private LookupResult components(String base,int page,int size,int offset,long started) throws SQLException {
        if(candidateLimit<1 || candidateLimit>100000)throw error("INVALID_CONFIG","component-max-candidates 必须为 1–100000。");
        matcher.match(List.of(),request.content(),sql.deadline);
        int scanned=0,found=0; LookupRecord last=null; List<LookupRecord> output=new ArrayList<>();
        while(found<=limits.maxResults()) {
            List<Object> values=new ArrayList<>(parameters); String cursor="";
            if(last!=null) {cursor=" WHERE (`time` < ? OR (`time` = ? AND `rowid` < ?))";values.addAll(List.of(last.time(),last.time(),last.rowId()));}
            int batchSize=Math.min(32,candidateLimit-scanned+1);
            List<LookupRecord> batch=load("SELECT *"+base+cursor+order()+" LIMIT "+batchSize,values);
            if(batch.isEmpty())break;
            if(scanned+batch.size()>candidateLimit)throw error("COMPONENT_SCAN_LIMIT","组件查询候选超出限制，搜索未完成；请缩小时间或范围。");
            BitSet matches=matcher.match(batch,request.content(),sql.deadline);
            if(System.nanoTime()>=sql.deadline)throw new SQLTimeoutException("Component query deadline expired");
            for(int i=0;i<batch.size() && found<=limits.maxResults();i++)if(matches.get(i)) {
                if(!request.options().countOnly() && found>=offset && found<offset+size && found<limits.maxResults())output.add(batch.get(i));
                found++;
            }
            scanned+=batch.size();last=batch.get(batch.size()-1);if(batch.size()<batchSize)break;
        }
        int total=Math.min(found,limits.maxResults());checkPage(total,page,offset);
        return result(page,size,total,found>limits.maxResults(),output,started);
    }
    private LookupResult result(int page,int size,int total,boolean capped,List<LookupRecord> records,long start) {
        return new LookupResult(true,null,"ok",request.action(),page,size,total,capped,(System.nanoTime()-start)/1_000_000,records,request.options().countOnly(),request.options().verbose());
    }
    private static void checkPage(int total,int page,int offset) {
        if((total==0 && page>1)||(total>0 && offset>=total))throw error("PAGE_OUT_OF_RANGE","页码超出查询结果范围。");
    }
    private String table(String source) {return switch(source) {
        case "block"->tables.block();case "item"->tables.item();case "container"->tables.container();case "chat"->tables.chat();case "command"->tables.command();
        case "sign"->tables.sign();case "session"->tables.session();case "username_log"->tables.username();default->throw new IllegalArgumentException(source);
    };}
    private Map<String,Long> ids(String table,String id,String label,List<String> values) throws SQLException {
        if(values.isEmpty())return Map.of();
        return sql.read("SELECT `"+id+"`,`"+label+"` FROM "+quote(table)+" WHERE LOWER(`"+label+"`) IN ("+marks(values.size())+")",values.stream().map(LookupPlan::lower).toList(),
                rs->{Map<String,Long> out=new LinkedHashMap<>();while(rs.next())out.put(lower(rs.getString(2)),rs.getLong(1));return out;});
    }
    private Map<Long,String> labels(String table,String id,String label,List<Long> raw) throws SQLException {
        List<Long> values=raw.stream().distinct().toList();if(values.isEmpty())return Map.of();
        return sql.read("SELECT `"+id+"`,`"+label+"` FROM "+quote(table)+" WHERE `"+id+"` IN ("+marks(values.size())+")",values,
                rs->{Map<Long,String> out=new HashMap<>();while(rs.next())out.put(rs.getLong(1),rs.getString(2));return out;});
    }
    private static void conditionalExclude(StringBuilder where,List<Object> values,List<Long> ids,String guard) {
        if(ids.isEmpty())return;where.append(" AND NOT (").append(guard).append(" AND `type` IN (").append(marks(ids.size())).append("))");values.addAll(ids);
    }
    private static void in(StringBuilder where,List<Object> values,String column,Collection<?> ids,boolean exclude) {
        if(ids.isEmpty())return;where.append(" AND `").append(column).append(exclude?"` NOT IN (":"` IN (").append(marks(ids.size())).append(')');values.addAll(ids);
    }
    private static String marks(int size){return String.join(",",Collections.nCopies(size,"?"));}
    private static String quote(String value){return "`"+value.replace(".","`.`")+"`";}
    private static String lower(String value){return value.toLowerCase(Locale.ROOT);}
    private static String entityName(String value){return lower(value).replaceFirst("^minecraft:","");}
    private static String order(){return " ORDER BY `time` DESC, `source_order` DESC, `rowid` DESC";}
    private static int sourceOrder(String source){return switch(source){case "container"->1;case "item"->2;default->0;};}
    private static Integer nullable(ResultSet rs,String name)throws SQLException{int n=rs.getInt(name);return rs.wasNull()?null:n;}
    private static QueryException error(String code,String message){return new QueryException(code,message);}
    private record Row(long id,long time,long user,long world,Integer x,Integer y,Integer z,long type,int amount,int action,long data,int rolledBack,String message,String uuid,String source,int sourceOrder) {
        boolean entity(){return source.equals("block") && action==3;}
        boolean typed(){return source.equals("block")||source.equals("container")||source.equals("item");}
    }
}
