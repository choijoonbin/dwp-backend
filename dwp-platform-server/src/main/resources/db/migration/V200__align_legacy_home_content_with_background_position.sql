-- V186 introduced independent content alignment with a LEFT default. Preserve the visible
-- legacy layout for records whose background previously implied centered or right-aligned copy.
-- Explicit non-default content alignment remains untouched.
UPDATE adm_home_experiences
   SET content_alignment = CASE background_position
           WHEN 'LEFT' THEN 'RIGHT'
           WHEN 'CENTER' THEN 'CENTER'
           ELSE content_alignment
       END
 WHERE content_alignment = 'LEFT'
   AND background_position IN ('LEFT', 'CENTER');
