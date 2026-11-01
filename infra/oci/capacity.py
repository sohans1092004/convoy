# Oracle's compute capacity report for convoy-lite's exact shape (VM.Standard.A1.Flex, 1 OCPU / 6 GB by default) in each
# availability domain of the home region. Prints one status per AD; exit 0 only if some AD reports AVAILABLE (the
# retry task runs plan/apply only then). Read-only: creates no resource. Uses ~/.oci/config; never prints its values.
#   python -P capacity.py [ocpus=1] [memory_gb=6]
import os, re, sys, configparser
import oci

def oci_config():
    p = configparser.ConfigParser()
    p.read(os.path.expanduser("~/.oci/config"))
    c = {k: re.sub(r"\s+#.*$", "", v).strip().strip("\"'") for k, v in p["DEFAULT"].items()}
    c["key_file"] = os.path.expanduser(c["key_file"])
    try:
        oci.config.validate_config(c)
    except Exception as e:
        sys.exit(f"~/.oci/config not usable ({type(e).__name__}); values not shown")
    return c

ocpus = float(sys.argv[1]) if len(sys.argv) > 1 else 1.0
mem = float(sys.argv[2]) if len(sys.argv) > 2 else 6.0
cfg = oci_config()
ads = [a.name for a in oci.identity.IdentityClient(cfg).list_availability_domains(cfg["tenancy"]).data]
compute = oci.core.ComputeClient(cfg)
available = False
for ad in ads:
    rep = compute.create_compute_capacity_report(oci.core.models.CreateComputeCapacityReportDetails(
        compartment_id=cfg["tenancy"], availability_domain=ad,
        shape_availabilities=[oci.core.models.CreateCapacityReportShapeAvailabilityDetails(
            instance_shape="VM.Standard.A1.Flex",
            instance_shape_config=oci.core.models.CapacityReportInstanceShapeConfig(ocpus=ocpus, memory_in_gbs=mem))])).data
    status = rep.shape_availabilities[0].availability_status
    print(f"{ad.split(':')[-1]}: {status}")
    available |= status == "AVAILABLE"
sys.exit(0 if available else 1)
