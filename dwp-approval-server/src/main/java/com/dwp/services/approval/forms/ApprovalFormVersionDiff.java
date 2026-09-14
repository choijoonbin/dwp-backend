package com.dwp.services.approval.forms;

import com.dwp.services.approval.forms.ApprovalFormLifecycleDtos.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

@Component
public class ApprovalFormVersionDiff {
    public Diff compare(Version from,Version to) {
        var changes=new ArrayList<Change>();
        boolean complete=walk("/schema",from.schema(),to.schema(),changes,0)
                &walk("/metadata",from.metadata(),to.metadata(),changes,0)
                &walk("/route",from.route(),to.route(),changes,0);
        return new Diff(from.formVersionId(),to.formVersionId(),from.schemaSha256(),to.schemaSha256(),List.copyOf(changes),complete,
                from.metadataProvenance(),to.metadataProvenance());
    }
    private boolean walk(String path,Object before,Object after,List<Change> changes,int depth) {
        if(Objects.equals(before,after)) return true;
        if(depth>64||changes.size()>=4096) return false;
        if(before instanceof Map<?,?> left&&after instanceof Map<?,?> right) {
            var keys=new TreeSet<String>();left.keySet().forEach(k->keys.add((String)k));right.keySet().forEach(k->keys.add((String)k));
            boolean complete=true;
            for(String key:keys) complete&=walk(path+"/"+key.replace("~","~0").replace("/","~1"),left.get(key),right.get(key),changes,depth+1);
            return complete;
        }
        if(before instanceof List<?> left&&after instanceof List<?> right) {
            boolean complete=true;
            for(int i=0;i<Math.max(left.size(),right.size());i++) complete&=walk(path+"/"+i,i<left.size()?left.get(i):null,i<right.size()?right.get(i):null,changes,depth+1);
            return complete;
        }
        changes.add(new Change(path,before,after));return true;
    }
}
