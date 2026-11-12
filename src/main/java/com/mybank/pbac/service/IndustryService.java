package com.mybank.pbac.service;
import com.mybank.pbac.model.*; import com.mybank.pbac.repository.*; import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional; import java.util.*;
@Service public class IndustryService{private final IndustryRepository repo;private final PolicyRepository policies;private final AuditService audit;
 public IndustryService(IndustryRepository r,PolicyRepository p,AuditService a){repo=r;policies=p;audit=a;} public List<Industry> all(){return repo.findAllByOrderByNameAsc();}
 public Industry one(Long id){return repo.findById(id).orElseThrow(()->new NoSuchElementException("Industry not found"));}
 @Transactional public Industry save(Long id,Industry x){Industry i=id==null?new Industry():one(id);String code=x.getCode().trim().toUpperCase();repo.findByCodeIgnoreCase(code).filter(v->!Objects.equals(v.getId(),id)).ifPresent(v->{throw new IllegalArgumentException("Industry code already exists: "+code);});i.setCode(code);i.setName(x.getName());i.setDescription(x.getDescription());i.setEnabled(x.isEnabled());i=repo.save(i);audit.log(id==null?"CREATE":"UPDATE","INDUSTRY",i.getId(),i.getCode());return i;}
 @Transactional public void disable(Long id){Industry i=one(id);i.setEnabled(false);audit.log("DISABLE","INDUSTRY",id,i.getCode()+"; policies="+policies.countByIndustryId(id));}
}
