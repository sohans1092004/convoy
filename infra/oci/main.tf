# Convoy lite host on Oracle Cloud (P6.D). Always Free resources only:
# - a VCN with one public subnet
# - a security list: SSH from one IP; 80/443 open
# - one VM.Standard.A1.Flex (1 OCPU, 6 GB, Ubuntu 24.04 aarch64, 50 GB boot volume), SSH keys only
# - an Object Storage bucket for encrypted Postgres backups (versioned, 7-day lifecycle; the VM gets a write-only link
#   made outside Terraform, scripts/create-backup-par.sh)
# The Always Free A1 allowance is 2 OCPU / 12 GB per tenancy (Oracle, since June 2026): Convoy takes 1 / 6, the other
# 1 / 6 is Reliant's. The instance refuses any plan that would take the tenancy's A1 total above 2 / 12 (below).
# Applied by a person after `terraform plan`.
terraform {
  required_version = ">= 1.6"
  required_providers {
    oci = { source = "oracle/oci", version = "~> 6.20" }
  }
  # Local state (git-ignored). No secrets: the backup link is made outside Terraform.
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

# ---------------------------------------------------------------- the Always Free A1 guard (2 OCPU / 12 GB per tenancy)
# Usage is counted tenancy-wide in every AD, minus convoy-lite itself if it already exists, plus what this plan asks for.
data "oci_limits_resource_availability" "a1" {
  for_each            = { for x in setproduct(["standard-a1-core-count", "standard-a1-memory-count"], data.oci_identity_availability_domains.ads.availability_domains[*].name) : "${x[0]}|${x[1]}" => x }
  compartment_id      = var.tenancy_ocid
  service_name        = "compute"
  limit_name          = each.value[0]
  availability_domain = each.value[1]
}

data "oci_core_instances" "self" {
  compartment_id = local.compartment
  display_name   = "convoy-lite"
  filter {
    name   = "state"
    values = ["RUNNING", "STARTING", "STOPPING", "STOPPED", "PROVISIONING"]
  }
}

locals {
  a1_used_ocpus  = sum([for k, r in data.oci_limits_resource_availability.a1 : coalesce(r.used, 0) if startswith(k, "standard-a1-core-count")])
  a1_used_gb     = sum([for k, r in data.oci_limits_resource_availability.a1 : coalesce(r.used, 0) if startswith(k, "standard-a1-memory-count")])
  self_ocpus     = sum(concat([0], [for i in data.oci_core_instances.self.instances : i.shape_config[0].ocpus]))
  self_gb        = sum(concat([0], [for i in data.oci_core_instances.self.instances : i.shape_config[0].memory_in_gbs]))
  a1_other_ocpus = local.a1_used_ocpus - local.self_ocpus
  a1_other_gb    = local.a1_used_gb - local.self_gb
}

data "oci_objectstorage_namespace" "ns" {
  compartment_id = var.tenancy_ocid
}
