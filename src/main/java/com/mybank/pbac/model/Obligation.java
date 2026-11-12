package com.mybank.pbac.model;
import jakarta.persistence.*; import jakarta.validation.constraints.*; import java.time.Instant;
@Entity @Table(name="mybank_obligations", uniqueConstraints=@UniqueConstraint(columnNames="code"))
public class Obligation {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @NotBlank @Column(nullable=false) private String code; @NotBlank @Column(nullable=false) private String name;
 @Column(length=2000) private String description; @Column(length=4000) private String parametersJson="{}";
 private boolean enabled=true; private Instant createdAt=Instant.now(); private Instant updatedAt=Instant.now();
 @PreUpdate void update(){updatedAt=Instant.now();}
 public Long getId(){return id;} public void setId(Long v){id=v;} public String getCode(){return code;} public void setCode(String v){code=v;}
 public String getName(){return name;} public void setName(String v){name=v;} public String getDescription(){return description;} public void setDescription(String v){description=v;}
 public String getParametersJson(){return parametersJson;} public void setParametersJson(String v){parametersJson=v;} public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;}
 public Instant getCreatedAt(){return createdAt;} public Instant getUpdatedAt(){return updatedAt;}
}
