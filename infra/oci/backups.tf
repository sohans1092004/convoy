# Encrypted nightly Postgres dumps (age, on the VM) go here. Always Free: 20 GB of Object Storage in the tenancy.
# The VM writes and reads through a pre-authenticated request (curl only, no OCI CLI or keys on the VM); objects older
# than 7 days are deleted by a lifecycle rule, so 7 nightly backups are kept.
resource "oci_objectstorage_bucket" "backups" {
  compartment_id = local.compartment
  namespace      = data.oci_objectstorage_namespace.ns.namespace
  name           = "convoy-backups"
  access_type    = "NoPublicAccess"
  versioning     = "Disabled"
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
  depends_on = [oci_identity_policy.lifecycle]
}

# Read/write to objects + listing, valid for a year (renew with `terraform apply -replace=oci_objectstorage_preauthrequest.backups`).
resource "oci_objectstorage_preauthrequest" "backups" {
  namespace             = data.oci_objectstorage_namespace.ns.namespace
  bucket                = oci_objectstorage_bucket.backups.name
  name                  = "convoy-lite-backups"
  access_type           = "AnyObjectReadWrite"
  bucket_listing_action = "ListObjects"
  time_expires          = timeadd(plantimestamp(), "8760h")
  lifecycle {
    ignore_changes = [time_expires]
  }
}
