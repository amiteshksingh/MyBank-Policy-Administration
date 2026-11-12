package com.mybank.pbac.model;
import jakarta.persistence.*; import jakarta.validation.constraints.*; import java.time.Instant;
@Entity @Table(name="mybank_attribute_definitions", uniqueConstraints=@UniqueConstraint(columnNames="attributeKey"))
public class AttributeDefinition {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id; @NotBlank @Column(nullable=false) private String attributeKey;
 @NotBlank private String displayName; @Enumerated(EnumType.STRING) private AttributeDataType dataType=AttributeDataType.STRING;
 private String description; @Column(length=4000) private String allowedValuesJson="[]"; private boolean enabled=true; private Instant createdAt=Instant.now();
 public Long getId(){return id;} public void setId(Long v){id=v;} public String getAttributeKey(){return attributeKey;} public void setAttributeKey(String v){attributeKey=v;}
 public String getDisplayName(){return displayName;} public void setDisplayName(String v){displayName=v;} public AttributeDataType getDataType(){return dataType;} public void setDataType(AttributeDataType v){dataType=v;}
 public String getDescription(){return description;} public void setDescription(String v){description=v;} public String getAllowedValuesJson(){return allowedValuesJson;} public void setAllowedValuesJson(String v){allowedValuesJson=v;}
 public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;} public Instant getCreatedAt(){return createdAt;}
}
