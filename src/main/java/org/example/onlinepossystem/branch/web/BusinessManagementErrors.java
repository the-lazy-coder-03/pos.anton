package org.example.onlinepossystem.branch.web;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestControllerAdvice(assignableTypes=BusinessStoreController.class)
public class BusinessManagementErrors {
 @ExceptionHandler({jakarta.persistence.PersistenceException.class,org.springframework.dao.DataAccessException.class})
 public ResponseEntity<?> database(RuntimeException failure){
  for(Throwable cause=failure;cause!=null;cause=cause.getCause()) if(cause instanceof java.sql.SQLException sql){
   String code=sql.getSQLState();
   if("42501".equals(code))return ResponseEntity.status(403).body(java.util.Map.of("message","Business Owner authorization required"));
   if("40001".equals(code)||"23505".equals(code))return ResponseEntity.status(409).body(java.util.Map.of("message","Store or command changed; reload and review"));
   if("23514".equals(code)||"22P02".equals(code))return ResponseEntity.badRequest().body(java.util.Map.of("message","Invalid store details"));
  }
  return ResponseEntity.status(503).body(java.util.Map.of("message","Store persistence unavailable"));
 }
}
