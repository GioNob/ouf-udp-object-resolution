-- The existing 4326 geometry is the serving representation, not the municipal canonical CRS.
alter table ouf_udp.urban_geometry drop constraint urban_geometry_canonical_srid_check;
alter table ouf_udp.urban_geometry add constraint urban_geometry_canonical_srid_check check(canonical_srid>0);
alter table ouf_udp.urban_geometry add column canonical_geometry geometry;
alter table ouf_udp.urban_geometry add column source_geometry_json jsonb;
alter table ouf_udp.urban_geometry add constraint urban_geometry_canonical_reference_check
  check((canonical_geometry is null and canonical_srid=4326) or
        (canonical_geometry is not null and ST_SRID(canonical_geometry)=canonical_srid));
comment on column ouf_udp.urban_geometry.geometry is 'Explicitly transformed EPSG:4326 serving representation; canonical_geometry retains the configured municipal CRS.';
comment on column ouf_udp.urban_geometry.source_geometry_json is 'Original CRS-tagged source envelope, unchanged; absent only for pre-R2d revisions.';
-- CRS review may precede creation of an Urban Object. The durable handoff remains the source reference.
alter table ouf_udp.spatial_resolution_issue alter column source_object_id drop not null;
alter table ouf_udp.spatial_resolution_issue drop constraint spatial_resolution_issue_reason_code_check;
alter table ouf_udp.spatial_resolution_issue add constraint spatial_resolution_issue_reason_code_check check(reason_code in(
 'SPATIAL_CRS_REQUIRED','SPATIAL_CRS_MISMATCH','SPATIAL_INVALID_GEOMETRY','SPATIAL_NO_MATCH','SPATIAL_MULTIPLE_MATCHES',
 'SPATIAL_POLICY_INVALID','SELF_LOOP_NOT_ALLOWED','SPATIAL_CRS_DECISION_REQUIRED','SPATIAL_CRS_REJECTED',
 'SPATIAL_TRANSFORM_UNAVAILABLE','SPATIAL_TRANSFORM_RESOURCE_CHANGED','SPATIAL_TRANSFORM_CONTROL_FAILED','SPATIAL_OUTSIDE_OPERATION_AREA'));

-- Exception boundary prevents malformed geometry/PROJ failures from aborting the issue transaction.
create function ouf_udp.project_geometry(input_json text,source_srid integer,target_srid integer,pipeline text,swap_xy boolean)
returns table(status text,ewkb text) language plpgsql as $$
declare g geometry;
begin
 begin
  g=ST_SetSRID(ST_GeomFromGeoJSON(input_json),source_srid);
 exception when others then
  return query select 'SPATIAL_INVALID_GEOMETRY'::text,null::text;return;
 end;
 if swap_xy then g=ST_FlipCoordinates(g);end if;
 if g is null or ST_IsEmpty(g) or ST_NPoints(g)>20000 or ST_CoordDim(g)<>2 or not ST_IsValid(g) then
  return query select 'SPATIAL_INVALID_GEOMETRY'::text,null::text;return;
 end if;
 if source_srid<>target_srid then
  if pipeline is null or btrim(pipeline)='' then return query select 'SPATIAL_CRS_DECISION_REQUIRED'::text,null::text;return;end if;
  g=ST_TransformPipeline(g,pipeline,target_srid);
 end if;
 if not ST_IsValid(g) or ST_IsEmpty(g) or
    not (ST_XMin(Box3D(g)) between -1e9 and 1e9 and ST_XMax(Box3D(g)) between -1e9 and 1e9 and
         ST_YMin(Box3D(g)) between -1e9 and 1e9 and ST_YMax(Box3D(g)) between -1e9 and 1e9) then
  return query select 'SPATIAL_INVALID_GEOMETRY'::text,null::text;return;
 end if;
 if target_srid=4326 and not (ST_XMin(Box3D(g))>=-180 and ST_XMax(Box3D(g))<=180 and ST_YMin(Box3D(g))>=-90 and ST_YMax(Box3D(g))<=90) then
  return query select 'SPATIAL_INVALID_GEOMETRY'::text,null::text;return;
 end if;
 return query select 'OK'::text,encode(ST_AsEWKB(g),'hex');
exception when others then
 return query select 'SPATIAL_TRANSFORM_UNAVAILABLE'::text,null::text;
end $$;
