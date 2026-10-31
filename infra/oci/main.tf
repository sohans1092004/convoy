# Convoy lite host on Oracle Cloud (P6.D). Always Free resources only:
# - a VCN with one public subnet
# - a security list: SSH from one IP; 80/443 open
# - one VM.Standard.A1.Flex (1 OCPU, 6 GB, Ubuntu 24.04 aarch64, 50 GB boot volume), SSH keys only
# - an Object Storage bucket for encrypted Postgres backups (7-day lifecycle, written through a pre-authenticated request)
# The other half of the A1 allowance (1 OCPU / 6 GB) stays free for Reliant. Applied by a person after `terraform plan`.
terraform {
  required_version = ">= 1.6"
  required_providers {
    oci = { source = "oracle/oci", version = "~> 6.20" }
  }
  # Local state (git-ignored): it holds the backup PAR URL.
}

provider "oci" {
  # API signing key from ~/.oci/config (profile DEFAULT): tenancy, user, fingerprint, key_file.
  config_file_profile = var.oci_profile
  region              = var.region
}

locals {
  compartment = var.compartment_ocid == "" ? var.tenancy_ocid : var.compartment_ocid
  tags        = { project = "convoy", profile = "lite" }
}

data "oci_identity_availability_domains" "ads" {
  compartment_id = var.tenancy_ocid
}

data "oci_core_images" "ubuntu" {
  compartment_id           = local.compartment
  operating_system         = "Canonical Ubuntu"
  operating_system_version = "24.04"
  shape                    = "VM.Standard.A1.Flex"
  sort_by                  = "TIMECREATED"
  sort_order               = "DESC"
}

data "oci_objectstorage_namespace" "ns" {
  compartment_id = var.tenancy_ocid
}
