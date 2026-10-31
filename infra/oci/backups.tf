# Encrypted nightly Postgres dumps (age, on the VM) go here. Always Free: 20 GB of Object Storage in the tenancy.
# The VM can only WRITE: a write-only pre-authenticated request made outside Terraform (scripts/create-backup-par.sh,
# straight into the git-ignored env file, never in state). Reading and restoring happen from the laptop with
# ~/.oci/config (deploy/lite/restore-check.sh). Versioning keeps an overwritten or deleted backup as a previous version,
# so a leaked write link cannot destroy history; lifecycle rules keep 7 days of current backups and 14 of old versions.
resource "oci_objectstorage_bucket" "backups" {
  compartment_id = local.compartment
  namespace      = data.oci_objectstorage_namespace.ns.namespace
  name           = "convoy-backups"
  access_type    = "NoPublicAccess"
  versioning     = "Enabled"
  freeform_tags  = local.tags
}

# Lifecycle rules run as the Object Storage service, which needs this policy in the tenancy.
resource "oci_identity_policy" "lifecycle" {
  compartment_id = var.tenancy_ocid
  name           = "convoy-backups-lifecycle"
  description    = "Object Storage may delete expired Convoy backups"
  statements     = ["Allow service objectstorage-${var.region} to manage object-family in compartment id ${local.compartment} where target.bucket.name = 'convoy-backups'"]
  freeform_tags  = local.tags
}

resource "oci_objectstorage_object_lifecycle_policy" "keep7" {
  namespace = data.oci_objectstorage_namespace.ns.namespace
  bucket    = oci_objectstorage_bucket.backups.name
  rules {
    name        = "delete-after-7-days"
    action      = "DELETE"
    is_enabled  = true
    target      = "objects"
    time_amount = 7
    time_unit   = "DAYS"
  }
  rules {
    name        = "delete-previous-versions-after-14-days"
    action      = "DELETE"
    is_enabled  = true
    target      = "previous-object-versions"
    time_amount = 14
    time_unit   = "DAYS"
  }
  depends_on = [oci_identity_policy.lifecycle]
}
