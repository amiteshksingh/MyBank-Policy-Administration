package com.mybank.pbac.model;
import jakarta.persistence.*; import java.time.Instant;
@Entity @Table(name="mybank_audit_events") public class AuditEvent {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id; private Instant eventTime=Instant.now(); private String action; private String entityType; private String entityId;
 @Column(length=8000) private String details; public AuditEvent(){} public AuditEvent(String a,String t,String i,String d){action=a;entityType=t;entityId=i;details=d;}
 public Long getId(){return id;} public Instant getEventTime(){return eventTime;} public String getAction(){return action;} public String getEntityType(){return entityType;} public String getEntityId(){return entityId;} public String getDetails(){return details;}
}
