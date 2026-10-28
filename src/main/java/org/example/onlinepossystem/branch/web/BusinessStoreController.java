package org.example.onlinepossystem.branch.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Existing cloud authentication + signed RLS context; never bypasses explicit business-owner grants. */
@RestController
@RequestMapping("/api/business-management/stores")
@ConditionalOnProperty(name="app.business-management.enabled",havingValue="true")
public class BusinessStoreController {
 private final EntityManager entities;
 private final ObjectMapper json;
 public BusinessStoreController(EntityManager entities,ObjectMapper json){this.entities=entities;this.json=json;}
 public record Save(UUID commandId,UUID businessId,UUID storeId,Long expectedRevision,JsonNode values){}
 @GetMapping @Transactional(readOnly=true)
 public JsonNode list(){return parse(entities.createNativeQuery("SELECT business_management.business_stores()::text").getSingleResult());}
 @PostMapping @Transactional
 public JsonNode save(@RequestBody Save command){
  if(command.commandId()==null||command.businessId()==null||command.values()==null||!command.values().isObject())
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Store command identity, business and values required");
  return parse(entities.createNativeQuery("SELECT business_management.manage_business_store(CAST(:command AS uuid),CAST(:business AS uuid),CAST(:store AS uuid),CAST(:revision AS bigint),CAST(:value AS jsonb))::text")
   .setParameter("command",command.commandId().toString()).setParameter("business",command.businessId().toString())
   .setParameter("store",command.storeId()==null?null:command.storeId().toString()).setParameter("revision",command.expectedRevision())
   .setParameter("value",command.values().toString()).getSingleResult());
 }
 private JsonNode parse(Object value){try{return json.readTree(value.toString());}catch(java.io.IOException invalid){throw new IllegalStateException("Invalid stored management response");}}
}
