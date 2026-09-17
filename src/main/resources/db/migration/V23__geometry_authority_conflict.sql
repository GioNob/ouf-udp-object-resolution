-- UDP PET §15.1: divergent equal-authority sources require review.
alter table ouf_udp.spatial_resolution_issue drop constraint spatial_resolution_issue_reason_code_check;
alter table ouf_udp.spatial_resolution_issue add constraint spatial_resolution_issue_reason_code_check check(reason_code in(
 'SPATIAL_CRS_REQUIRED','SPATIAL_CRS_MISMATCH','SPATIAL_INVALID_GEOMETRY','SPATIAL_NO_MATCH','SPATIAL_MULTIPLE_MATCHES',
 'SPATIAL_POLICY_INVALID','SELF_LOOP_NOT_ALLOWED','SPATIAL_CRS_DECISION_REQUIRED','SPATIAL_CRS_REJECTED',
 'SPATIAL_TRANSFORM_UNAVAILABLE','SPATIAL_TRANSFORM_RESOURCE_CHANGED','SPATIAL_TRANSFORM_CONTROL_FAILED','SPATIAL_OUTSIDE_OPERATION_AREA','SPATIAL_AUTHORITY_CONFLICT'));
